/*
 * This file is part of Alpine Data Labs' R Connector (henceforth " R Connector").
 * R Connector is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * R Connector is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with R Connector.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.alpine.rconnector.server

import java.io.{File, FileOutputStream}
import java.security.KeyStore
import java.util.{List => JList, Map => JMap}

import akka.AkkaException
import akka.actor.Actor
import akka.event.Logging
import com.alpine.rconnector.messages._
import com.alpine.rconnector.server.RServeMain.autoDeleteTempFiles
import org.apache.commons.io.IOUtils
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.conn.ConnectTimeoutException
import org.apache.http.conn.ssl.{SSLConnectionSocketFactory, SSLContexts, TrustSelfSignedStrategy}
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.entity.mime.{HttpMultipartMode, MultipartEntityBuilder}
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.{HttpStatus, HttpVersion}
import org.rosuda.REngine.Rserve.RConnection

import scala.collection.JavaConversions._
import scala.sys.process._
import scala.util.{Failure, Success, Try}

/**
 * This is the actor that establishes a connection to R via Rserve
 * (see <a href="http://rforge.net/Rserve/">Rserve documentation</a>).
 * <br>
 * TCP connections to R are kept for as long as the actor is alive.
 * If the actor is killed by its supervisor or throws an exception, the connection
 * gets released.
 */
class RServeActor extends Actor {

  private[this] implicit val log = Logging(context.system, this)

  protected[this] var conn: RConnection = _
  protected[this] var pid: Int = _
  protected[this] var tempFilePath: String = _

  val downloadExtension = "download"
  val uploadExtension = "upload"
  val parseBoilerplate = "parse(text = rawScript)"

  // trust self-signed SSL certificates
  val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
  val sslCtx = SSLContexts.custom().loadTrustMaterial(trustStore, new TrustSelfSignedStrategy()).build()
  val sslConnFactory = new SSLConnectionSocketFactory(
    sslCtx,
    Array("SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2"),
    null,
    SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)

  /*
    *** Note ***
    ALLOW_ALL_HOSTNAME_VERIFIER doesn't check domain correctness, e.g. it will accept
    localhost/127.0.0.1 instead of alpineqa3.alpinenow.local/10.0.0.204
    Set to BROWSER_COMPATIBLE_HOSTNAME_VERIFIER and use correctly issued
    SSL certificates if this is a problem.
   */

  def updateConnAndPid() = {
    conn = new RConnection()
    pid = conn.eval("Sys.getpid()").asNativeJavaObject.asInstanceOf[Array[Int]](0)
    log.info(s"New R PID is $pid")
    context.parent ! PId(pid)
  }

  override def preStart(): Unit =
    updateConnAndPid()

  logActorStart(this)

  private def killRProcess(): Int = {
    log.info(s"Killing R process")
    s"kill -9 $pid" !
  }

  override def postStop(): Unit =
    killRProcess()

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    // send message about exception to the client (e.g. for UI reporting)
    log.info("preRestart")
    sender ! RException(reason)
    killRProcess()
    super.preRestart(reason, message)
  }

  // that's the default anyway, but you can do something different
  override def postRestart(reason: Throwable): Unit = {
    log.info("postRestart")
    preStart()
  }

  // remove all temporary data from R workspace
  protected[this] def clearWorkspace() =
    conn.eval("rm(list = ls())")

  private def eval: String => java.lang.Object = RExecution.eval(conn, log)

  def receive: Receive = {

    case RAssign(uuid, objects, httpDownloadUrl, httpDownloadHeader) => {

      log.info(s"Received RAssign message")

      if (httpDownloadUrl != None && httpDownloadHeader != None) {
        log.info("Download URL and header are present - performing REST download")
        restDownload(httpDownloadUrl, httpDownloadHeader, uuid)
      }

      log.info("Assigning objects (if any) to R workspace")
      objects.foreach {
        case (x, y: String) =>
          conn.assign(x, y)
        case (x, y: Array[Byte]) =>
          conn.assign(x, y)
        case (x, y) =>
          throw new IllegalStateException(
            s"Unsupported type of value $x Expected either String or Array[Byte] but got ${y.getClass.getName}"
          )
      }

      log.info("Acking assignment to client")
      sender ! AssignAck(uuid, objects.keySet.map(_.toString).toArray)
    }

    case SyntaxCheckRequest(uuid, rScript) => {
      log.info("Evaluating syntax check request")
      conn.assign("rawScript", rScript)

      try {
        eval(parseBoilerplate)
      } catch {

        case e: Exception => {
          deleteTempFiles(uuid)

          val msg = e.getMessage
          val errorIn = "Error in"

          if (msg.contains(parseBoilerplate)) {
            val noBoilerplate = msg.replace(parseBoilerplate, "")
            val noErrorIn =
              if (noBoilerplate.startsWith(errorIn)) noBoilerplate.replaceFirst(errorIn, "")
              else noBoilerplate
            val leadingRegex = "[^a-zA-Z]+text[^a-zA-Z]+".r
            val finalStr = leadingRegex.replaceFirstIn(noErrorIn, "")

            throw new RuntimeException(finalStr)

          } else {
            throw e
          }
        }
      }

      log.info("Evaluation successful. Sending response back to Alpine.")
      // We won't get here if an exception occurred
      sender ! SyntaxCheckOK(uuid)
    }

    case HadoopExecuteRRequest(
      uuid,
      rScript,
      Some(returnNames),
      numPreviewRows,
      Some(escapeStr),
      Some(inputDelimiterStr),
      Some(outputDelimiterStr),
      Some(quoteStr),
      httpUploadUrl,
      httpUploadHeader,
      columnNames) => {

      log.info("Received HadoopExecuteRRequest. Evaluating enriched script")

      val rResponse: Message = Try({

        val r = processRequest(
          uuid,
          rScript,
          returnNames,
          numPreviewRows,
          escapeStr,
          inputDelimiterStr,
          outputDelimiterStr,
          quoteStr,
          columnNames)

        hadoopRestUpload(httpUploadUrl, httpUploadHeader, uuid)

        r
      }) match {
        case Success(r) => r
        case Failure(e) => RException(e)
      }

      if (autoDeleteTempFiles) {
        deleteTempFiles(uuid)
      }

      log.info(s"Sending R response to client")
      sender ! rResponse
    }

    case DBExecuteRRequest(
      uuid,
      rScript,
      Some(returnNames),
      numPreviewRows,
      Some(escapeStr),
      Some(inputDelimiterStr),
      Some(outputDelimiterStr),
      Some(quoteStr),
      httpUploadUrl,
      httpUploadHeader,
      schemaName,
      tableName) => {

      log.info("Received HadoopExecuteRRequest. Evaluating enriched script")

      val rResponse: Message = Try({

        val r = processRequest(
          uuid,
          rScript,
          returnNames,
          numPreviewRows,
          escapeStr,
          inputDelimiterStr,
          outputDelimiterStr,
          quoteStr,
          None)

        dbRestUpload(
          httpUploadUrl,
          httpUploadHeader,
          uuid,
          outputDelimiterStr,
          quoteStr,
          escapeStr,
          schemaName,
          tableName)

        r
      }) match {
        case Success(r) => r
        case Failure(e) => RException(e)
      }

      if (autoDeleteTempFiles) {
        deleteTempFiles(uuid)
      }

      log.info(s"Sending R response to client")
      sender ! rResponse
    }

    case FinishRSession(uuid) => {
      log.info(s"Finishing R session for UUID $uuid")
      killRProcess()
      updateConnAndPid()
      log.info(s"Sending RSessionFinishedAck")

      if (autoDeleteTempFiles) {
        deleteTempFiles(uuid)
      }

      sender ! RSessionFinishedAck(uuid)
    }

    case other => {
      val errMsg = s"Unexpected message of type ${other.getClass.getName} from $sender"
      log.error(errMsg)
      throw new AkkaException(errMsg)
    }

  }

  // client will pass in the header info, depending on whether it's DB or Hadoop
  // mutable map is necessary due to implicit conversion from java.util.Map
  private def restDownload(url: Option[String], header: Option[JMap[String, String]], uuid: String): Unit = {

    if (url != None && header != None) {

      val localPath = downloadLocalPath(uuid)

      log.info(
        s"""Starting download from ${url.get}
        with header ${header.get}
        into local file $localPath
        """.stripMargin)

      // TODO: refactor with try-with-resources (Josh Suereth's scala-arm library)
      var client: CloseableHttpClient = null
      var fos: FileOutputStream = null
      var get: HttpGet = null

      try {

        client = HttpClients.custom()
          .setSSLSocketFactory(sslConnFactory)
          .setRedirectStrategy(new LaxPostRedirectStrategy()) // LaxRedirectStrategy
          .build()

        fos = new FileOutputStream(new File(localPath))

        get = new HttpGet(url.get) {
          setProtocolVersion(HttpVersion.HTTP_1_1) // ensure chunking
          header.get.foreach { case (k, v) => setHeader(k, v) }
        }

        val response = client.execute(get)
        val statusLine = response.getStatusLine
        val statusCode = statusLine.getStatusCode

        if (statusCode != HttpStatus.SC_OK) {

          val responseContent = IOUtils.toString(response.getEntity.getContent, "UTF-8")
          log.error(responseContent)

          val excMsg = Utils.alpineUpDownLoadErrMsg(
            "REST download of R dataset from Alpine to R server failed")(statusCode, responseContent)
          log.error(excMsg)

          throw new RuntimeException(excMsg)
        }

        response.getEntity.writeTo(fos)

        log.info(s"File $localPath downloaded successfully")

      } catch {

        case e: ConnectTimeoutException => {
          if (autoDeleteTempFiles) {
            deleteDownloadTempFile(uuid)
          }

          throw new RuntimeException(e.getMessage)
        }

        case e: Exception => {
          if (autoDeleteTempFiles) {
            deleteDownloadTempFile(uuid)
          }

          throw new RuntimeException(e.getMessage)
        }

      } finally {

        if (client != null) {
          client.close()
        }

        if (fos != null) {
          fos.close()
        }

        if (get != null) {
          get.releaseConnection()
        }
      }
    }
  }

  // mutable map is necessary due to implicit conversion from java.util.Map
  private def hadoopRestUpload(url: Option[String], header: Option[JMap[String, String]], uuid: String): Unit = {

    log.info("In hadoopRestUpload")
    if (url != None && header != None) {

      val localPath = uploadLocalPath(uuid)
      log.info(
        s"""Starting upload to $url
        with header $header
        from local file $localPath
        """.stripMargin)

      // TODO: refactor with try-with-resources (Josh Suereth's scala-arm library)
      var client: CloseableHttpClient = null
      var post: HttpPost = null

      try {

        client = HttpClients
          .custom()
          .setSSLSocketFactory(sslConnFactory)
          .setRedirectStrategy(new LaxPostRedirectStrategy()) // LaxRedirectStrategy
          .build()

        println(s"\n\nHTTP POST to URL ${url.get}\n\n")

        post = new HttpPost(url.get) {
          setProtocolVersion(HttpVersion.HTTP_1_1) // ensure chunking
          header.get.foreach { case (k, v) => setHeader(k, v) }
        }

        val entity = MultipartEntityBuilder
          .create()
          .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
          .addBinaryBody("file", new File(localPath))
          .build()

        post.setEntity(entity)

        val response = client.execute(post)
        val statusLine = response.getStatusLine
        val statusCode = statusLine.getStatusCode

        if (statusCode != HttpStatus.SC_OK) {
          val responseContent = IOUtils.toString(response.getEntity.getContent, "UTF-8")
          log.error(responseContent)

          val excMsg = Utils.alpineUpDownLoadErrMsg(
            "REST upload of dataset from Alpine to R server failed")(statusCode, responseContent)

          log.error(excMsg)

          throw new RuntimeException(excMsg)
        }

        log.info(s"File $localPath uploaded successfully")

      } catch {

        case e: ConnectTimeoutException => {
          deleteUploadTempFile(uuid)
          throw new RuntimeException(e.getMessage)
        }

        case e: Exception => {
          if (autoDeleteTempFiles) {
            deleteTempFiles(uuid)
          }

          throw new RuntimeException(e)
        }

      } finally {

        if (client != null) {
          client.close()
        }

        if (post != null) {
          post.releaseConnection()
        }
      }
    }
  }

  private def dbRestUpload(url: Option[String], header: Option[JMap[String, String]], uuid: String,
    delimiterStr: String, quoteStr: String, escapeStr: String,
    schemaName: Option[String], tableName: Option[String]): Unit = {

    log.info("In dbRestUpload")

    if (url != None && header != None && schemaName != None && tableName != None) {

      val localPath = uploadLocalPath(uuid)

      log.info("Making file upload REST call")

      // TODO: refactor with try-with-resources (Josh Suereth's scala-arm library)
      var client: CloseableHttpClient = null
      var post: HttpPost = null

      try {

        client = HttpClients
          .custom()
          .setSSLSocketFactory(sslConnFactory)
          .setRedirectStrategy(new LaxPostRedirectStrategy()) // LaxRedirectStrategy
          .build()

        post = new HttpPost(url.get)

        //          header.get.foreach { case (k, v) => post.setHeader(k, v)}

        val entity = MultipartEntityBuilder
          .create()
          .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
          .addBinaryBody("file", new File(localPath))

        // These are form elements, not header elements
        header.get.foreach {
          case (k, v) =>

            entity.addPart(k, new StringBody(v, ContentType.TEXT_PLAIN))

        }

        val metadata = AlpineFileTransfer.getDBUploadMeta(eval)(
          dfName = "alpine_output",
          schemaName = schemaName.get,
          tableName = tableName.get,
          delimiter = delimiterStr,
          quote = quoteStr,
          escape = escapeStr,
          limitNum = -1,
          includeHeader = true
        )

        entity.addPart("fileMetadata", new StringBody(metadata, ContentType.TEXT_PLAIN))

        println(s"\n\nURL is ${url.get} \n\n")

        post.setEntity(entity.build())

        println(s"\n\nMetadata\n\n${metadata.toString}\n\n")

        val response = client.execute(post)
        val statusLine = response.getStatusLine
        val statusCode = statusLine.getStatusCode

        if (statusCode != HttpStatus.SC_OK) {

          val responseContent = IOUtils.toString(response.getEntity.getContent, "UTF-8")
          log.error(responseContent)

          val errMsg = Utils.alpineUpDownLoadErrMsg("REST upload from R server to Alpine failed")(statusCode, responseContent)
          log.error(errMsg)

          throw new RuntimeException(errMsg)
        }

        log.info(s"File $localPath uploaded successfully")

      } catch {

        case e: Exception => {

          if (autoDeleteTempFiles) {

            deleteTempFiles(uuid)
          }
          throw new RuntimeException(e)
        }

      } finally {

        if (client != null) {

          client.close()
        }

        if (post != null) {

          post.releaseConnection()
        }
      }

    }
  }

  private def deleteTempFile(localPath: String, ifExists: Boolean = true): Unit =
    Try(new File(localPath).delete()) match {

      case Success(_) =>
      case Failure(err) => if (!ifExists) throw new RuntimeException(err.getMessage)
    }

  private def deleteDownloadTempFile(uuid: String): Unit = deleteTempFile(downloadLocalPath(uuid))

  private def deleteUploadTempFile(uuid: String): Unit = deleteTempFile(uploadLocalPath(uuid))

  private def deleteTempFiles(uuid: String): Unit = {

    deleteDownloadTempFile(uuid)
    deleteUploadTempFile(uuid)
  }

  private def enrichRScript(rawScript: String,
    consoleOutputVar: String,
    inputPath: String,
    outputPath: String,
    inputDelimiterStr: String,
    outputDelimiterStr: String,
    previewNumRows: Long,
    columnNames: Option[JList[String]]): String = {

    def assignColumnNames(objName: String, l: JList[String]) =
      l.mkString(s"""names($objName) <- c('""", "', '", "')")

    /* In fread, it should be as.data.frame(fread(input='$inputPath', sep='$delimiterStr')).
       However, the problem is that fread fails if the delimiter isn't specified as auto or '\n'
       and there is only one column, i.e. the delimiter doesn't exist because the input file
       has a single column. So, I'm leaving this as auto for now. The Scala code could handle the distinction
       between the actual delimieteStr and '\n' for a single column, but that would then require handling
       of the escaping and quoting correctly to count the number of columns. Alternatively, the
       RAssign case class could provide the input column count, and if it's
       equal to 1, the separator could be set to 1.
      */

    val enrichedScript = s"""
            $consoleOutputVar <- capture.output({

            library(data.table);

            ${if (hasInput(rawScript)) s"alpine_input <- as.data.frame(fread(input='$inputPath', sep='$inputDelimiterStr'));" else ""}

            ${if (columnNames != None) assignColumnNames("alpine_input", columnNames.get) else ""}

            $rawScript

            ${
      if (hasOutput(rawScript))

        s"""# write temp table to disk
                  if (class(alpine_output) != 'data.frame') {
                    stop(sprintf('Class of alpine_output is not data.frame but %s. Did you try to return something other than a data frame or did R coerce the type?', class(alpine_output)))
                  }
                  write.table(x = alpine_output, file='$outputPath', sep='$outputDelimiterStr', append=FALSE, quote=FALSE, row.names=FALSE)
                  # preview this many rows in UI
                  # need to handle a degenerate case of a single-column data frame, which will be type coerced by R
                  alpineOutputColNames <- names(alpine_output)
                  alpine_output <- alpine_output[1:min($previewNumRows, nrow(alpine_output)),]
                  if (class(alpine_output) != 'data.frame') {
                    alpine_output <- as.data.frame(alpine_output)
                    names(alpine_output) <- alpineOutputColNames
                  }
                """
      else ""
    }
            });""".stripMargin

    log.info(s"Enriched script:\n$enrichedScript")
    enrichedScript
  }

  private def downloadLocalPath(uuid: String): String =
    s"${RServeMain.localTempDir}/$uuid.$downloadExtension"

  private def uploadLocalPath(uuid: String): String =
    s"${RServeMain.localTempDir}/$uuid.$uploadExtension"

  private def hasInput(rScript: String): Boolean =
    Utils.containsNotInComment(rScript, "alpine_input", "#")

  private def hasOutput(rScript: String): Boolean =
    Utils.containsNotInComment(rScript, "alpine_output", "#")

  private def processRequest(
    uuid: String,
    rScript: String,
    returnNames: ReturnNames,
    numPreviewRows: Long,
    escapeStr: String,
    inputDelimiterStr: String,
    outputDelimiterStr: String,
    quoteStr: String,
    columnNames: Option[JList[String]]): RResponse = {

    // execute R script
    val escript = enrichRScript(
      rScript,
      returnNames.rConsoleOutput,
      downloadLocalPath(uuid),
      uploadLocalPath(uuid),
      inputDelimiterStr,
      outputDelimiterStr,
      numPreviewRows,
      columnNames)

    eval(escript)

    val rConsoleOutput = eval(returnNames.rConsoleOutput).asInstanceOf[Array[String]]

    val dataPreview =
      if (hasOutput(rScript)) {
        Some(eval(returnNames.outputDataFrame.get).asInstanceOf[JMap[String, Object]])
      } else {
        None
      }

    RResponse(rConsoleOutput, dataPreview)
  }

}