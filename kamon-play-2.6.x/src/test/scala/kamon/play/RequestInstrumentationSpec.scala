/* =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.play

import javax.inject.Inject
import kamon.Kamon
import kamon.context.Context
import kamon.play.action.OperationName
import kamon.testkit.MetricInspection
import kamon.trace.Span
import kamon.trace.Span.TagValue
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.SpanSugar
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatestplus.play._
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.http.HttpFilters
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.{StandaloneWSRequest, WSClient}
import play.api.mvc.Results.{NotFound, Ok}
import play.api.mvc._
import play.api.mvc.request.RequestAttrKey
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.{ExecutionContext, Future}

class AkkaHTTPRequestHandlerInstrumentationSpec extends {
  val confFile = "./kamon-play-2.6.x/src/test/resources/conf/application-akka-http.conf"
  //https://www.playframaework.com/documentation/2.6.x/NettyServer#Verifying-that-the-Netty-server-is-running
  //The Akka HTTP backend will not set a value for this request attribute.
  val expectedServer = "undefined"
} with RequestHandlerInstrumentationSpec

class NettyRequestHandlerInstrumentationSpec extends {
  val confFile = "./kamon-play-2.6.x/src/test/resources/conf/application-netty.conf"
  val expectedServer = "netty"
} with RequestHandlerInstrumentationSpec

abstract class RequestHandlerInstrumentationSpec extends PlaySpec with GuiceOneServerPerSuite
  with ScalaFutures
  with Eventually
  with SpanSugar
  with BeforeAndAfterAll
  with MetricInspection
  with OptionValues
  with SpanReporter {

  val confFile: String
  val expectedServer: String

  System.setProperty("config.file", confFile)

  override lazy val port: Port = 19002

  implicit val executor: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  val requestIDTag = "request-id"
  val withRoutes: PartialFunction[(String, String), Handler] = {
    case ("GET", "/ok") ⇒ Action { Ok }
    case ("GET", "/request-id") ⇒ Action { Ok(Kamon.currentContext().getTag(requestIDTag).getOrElse("undefined")) }
    case ("GET", "/async") ⇒ Action.async { Future { Ok } }
    case ("GET", "/not-found") ⇒ Action { NotFound }
    case ("GET", "/server") ⇒ Action { req => Ok(req.attrs.get(RequestAttrKey.Server).getOrElse("undefined")) }
    case ("GET", "/renamed") ⇒
      OperationName("renamed-operation") {
        Action.async {
          Future {
            Ok("async")
          }
        }
      }
    case ("GET", "/error") ⇒ Action {
      throw new Exception("This page generates an error!")
      Ok("This page will generate an error!")
    }
  }

  val additionalConfiguration: Map[String, _] = Map(
    ("logger.root", "OFF"),
    ("logger.play", "OFF"),
    ("logger.application", "OFF"))


  override def fakeApplication(): Application = new GuiceApplicationBuilder()
    .configure(additionalConfiguration)
    .routes(withRoutes)
    .build

  "the Request instrumentation" should {

    "check the right server is configured" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val endpoint = s"http://localhost:$port/server"

      val response = await(wsClient.url(endpoint).get())
      response.status mustBe 200
      response.body mustBe expectedServer
    }

    "propagate the current context and respond to the ok action" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val okSpan = Kamon.buildSpan("ok-operation-span").start()
      val endpoint = s"http://localhost:$port/ok"

      Kamon.withContext(Context.of(Span.ContextKey, okSpan)) {
        val response = await(wsClient.url(endpoint).get())
        response.status mustBe 200
      }

      eventually(timeout(5 seconds)) {
        val span = reporter.nextSpan().value
        span.operationName mustBe "GET:/ok"
        span.tags("span.kind") mustBe TagValue.String("server")
        span.tags("http.method") mustBe TagValue.String("GET")
        span.tags("http.status_code") mustBe TagValue.Number(200)
      }
    }

    "decode insensitive headers" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val okSpan = Kamon.buildSpan("ok-operation-span").start()
      val endpoint = s"http://localhost:$port/request-id"

      {
        val response = await(wsClient.url(endpoint).withHeaders("X-Request-ID" -> "123456").get())
        response.status mustBe 200
        response.body mustBe "123456"
      }
      {
        val response = await(wsClient.url(endpoint).withHeaders("X-Request-Id" -> "123456").get())
        response.status mustBe 200
        response.body mustBe "123456"
      }
    }

    "propagate automatic broadcast string keys" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val okSpan = Kamon.buildSpan("ok-operation-span").start()
      val endpoint = s"http://localhost:$port/request-id"

      Kamon.withContext(Context.of(Span.ContextKey, okSpan).withTag(requestIDTag, "123456")) {
        val response = await(wsClient.url(endpoint).get())
        response.status mustBe 200
        response.body mustBe "123456"
      }
    }

    "propagate the current context and respond to the async action" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val asyncSpan = Kamon.buildSpan("async-operation-span").start()
      val endpoint = s"http://localhost:$port/async"

      Kamon.withContext(Context.of(Span.ContextKey, asyncSpan)) {
        val response = await(wsClient.url(endpoint).get())
        response.status mustBe 200
      }

      eventually(timeout(2 seconds)) {
        val span = reporter.nextSpan().value
        span.operationName mustBe "GET:/async"
        span.tags("span.kind") mustBe TagValue.String("server")
        span.tags("http.method") mustBe TagValue.String("GET")
        span.tags("http.status_code") mustBe TagValue.Number(200)
      }
    }

    "propagate the current context and respond to the not-found action" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val notFoundSpan = Kamon.buildSpan("not-found-operation-span").start()
      val endpoint = s"http://localhost:$port/not-found"

      Kamon.withContext(Context.of(Span.ContextKey, notFoundSpan)) {
        val response = await(wsClient.url(endpoint).get())
        response.status mustBe 404
      }

      eventually(timeout(2 seconds)) {
        val span = reporter.nextSpan().value
        span.operationName mustBe "not-found"
        span.tags("span.kind") mustBe TagValue.String("server")
        span.tags("http.method") mustBe TagValue.String("GET")
        span.tags("http.status_code") mustBe TagValue.Number(404)
      }
    }

    "propagate the current context and respond to the renamed action" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val renamedSpan = Kamon.buildSpan("renamed-operation-span").start()
      val endpoint = s"http://localhost:$port/renamed"

      Kamon.withContext(Context.of(Span.ContextKey, renamedSpan)) {
        val response = await(wsClient.url(endpoint).get())
        response.status mustBe 200
      }

      eventually(timeout(2 seconds)) {
        val span = reporter.nextSpan().value
        span.operationName mustBe "renamed-operation"
        span.tags("span.kind") mustBe TagValue.String("server")
        span.tags("http.method") mustBe TagValue.String("GET")
        span.tags("http.status_code") mustBe TagValue.Number(200)
      }
    }


    "propagate the current context and respond to the error action" in {
      val wsClient = app.injector.instanceOf[WSClient]
      val errorSpan = Kamon.buildSpan("error-operation-span").start()
      val endpoint = s"http://localhost:$port/error"

      Kamon.withContext(Context.of(Span.ContextKey, errorSpan)) {
        val response = await(wsClient.url(endpoint).get())
        response.status mustBe 500
      }

      eventually(timeout(2 seconds)) {
        val span = reporter.nextSpan().value
        span.operationName mustBe "GET:/error"
        span.tags("span.kind") mustBe TagValue.String("server")
        span.tags("http.method") mustBe TagValue.String("GET")
        span.tags("error") mustBe TagValue.True
        span.tags("http.status_code") mustBe TagValue.Number(500)
      }
    }
  }
}


class TestHttpFilters @Inject() (kamonFilter: OperationNameFilter) extends HttpFilters {
  val filters = Seq(kamonFilter)
}

class TestNameGenerator extends NameGenerator {
  import java.util.Locale

  import play.api.routing.Router

  import scala.collection.concurrent.TrieMap

  private val cache = TrieMap.empty[String, String]
  private val normalizePattern = """\$([^<]+)<[^>]+>""".r

  def generateOperationName(requestHeader: RequestHeader): String = requestHeader.attrs.get(Router.Attrs.HandlerDef).map { handlerDef ⇒
    cache.getOrElseUpdate(s"${handlerDef.verb}${handlerDef.path}", {
      val traceName = {
        // Convert paths of form GET /foo/bar/$paramname<regexp>/blah to foo.bar.paramname.blah.get
        val p = normalizePattern.replaceAllIn(handlerDef.path, "$1").replace('/', '.').dropWhile(_ == '.')
        val normalisedPath = {
          if (p.lastOption.exists(_ != '.')) s"$p."
          else p
        }
        s"$normalisedPath${handlerDef.verb.toLowerCase(Locale.ENGLISH)}"
      }
      traceName
    })
  } getOrElse s"${requestHeader.method}:${requestHeader.uri}"

  def generateHttpClientOperationName(request: StandaloneWSRequest): String = request.url
}
