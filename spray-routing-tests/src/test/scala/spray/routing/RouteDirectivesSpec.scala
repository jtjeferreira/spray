/*
 * Copyright © 2011-2015 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.routing

import scala.concurrent.{ Future, Promise }
import spray.http._
import spray.util._
import HttpHeaders._
import StatusCodes._
import MediaTypes._
import spray.httpx.marshalling.ToResponseMarshallable
import spray.httpx.SprayJsonSupport
import spray.httpx.marshalling.{ ToResponseMarshallingContext, MarshallingContext, ToResponseMarshaller, Marshaller }
import akka.actor.ActorRef

class RouteDirectivesSpec extends RoutingSpec {

  "The `complete` directive" should {
    "by chainable with the `&` operator" in {
      Get() ~> (get & complete("yeah")) ~> check { responseAs[String] === "yeah" }
    }
    "allow for factoring out a StandardRoute" in {
      Get() ~> (get & complete)("yeah") ~> check { responseAs[String] === "yeah" }
    }
    "be lazy in its argument evaluation, independently of application style" in {
      var i = 0
      Put() ~> {
        get { complete { i += 1; "get" } } ~
          put { complete { i += 1; "put" } } ~
          (post & complete { i += 1; "post" }) ~
          (delete & complete) { i += 1; "delete" }
      } ~> check {
        responseAs[String] === "put"
        i === 1
      }
    }
    "support completion from response futures" in {
      "simple case without marshaller" in {
        Get() ~> {
          get & complete(Promise.successful(HttpResponse(entity = "yup")).future)
        } ~> check { responseAs[String] === "yup" }
      }
      "for successful futures and marshalling" in {
        Get() ~> complete(Promise.successful("yes").future) ~> check { responseAs[String] === "yes" }
      }
      "for failed futures and marshalling" in {
        object TestException extends spray.util.SingletonException
        Get() ~> complete(Promise.failed[String](TestException).future) ~>
          check {
            status === StatusCodes.InternalServerError
            responseAs[String] === "There was an internal server error."
          }
      }
      "for futures failed with a RejectionError" in {
        Get() ~> complete(Promise.failed[String](RejectionError(AuthorizationFailedRejection)).future) ~>
          check {
            rejection === AuthorizationFailedRejection
          }
      }
    }
    "allow easy handling of futured ToResponseMarshallers" in {
      trait RegistrationStatus
      case class Registered(name: String) extends RegistrationStatus
      case object AlreadyRegistered extends RegistrationStatus

      val route =
        get {
          path("register" / Segment) { name ⇒
            def registerUser(name: String): Future[RegistrationStatus] = Future.successful {
              name match {
                case "otto" ⇒ AlreadyRegistered
                case _      ⇒ Registered(name)
              }
            }
            complete {
              registerUser(name).map[ToResponseMarshallable] {
                case Registered(_) ⇒ HttpData.Empty
                case AlreadyRegistered ⇒
                  import spray.json.DefaultJsonProtocol._
                  import spray.httpx.SprayJsonSupport._
                  (StatusCodes.BadRequest, Map("error" -> "User already Registered"))
              }
            }
          }
        }

      Get("/register/otto") ~> route ~> check {
        status === StatusCodes.BadRequest
      }
      Get("/register/karl") ~> route ~> check {
        status === StatusCodes.OK
        entity === HttpEntity.Empty
      }
    }
    "do Content-Type negotiation for multi-marshallers" in {
      val route = get & complete(OK -> Data("Ida", 83))

      import HttpHeaders.Accept
      Get().withHeaders(Accept(MediaTypes.`application/json`)) ~> route ~> check {
        responseAs[String] ===
          """{
            |  "name": "Ida",
            |  "age": 83
            |}""".stripMarginWithNewline("\n")
      }
      Get().withHeaders(Accept(MediaTypes.`text/xml`)) ~> route ~> check {
        responseAs[xml.NodeSeq] === <data><name>Ida</name><age>83</age></data>
      }
      Get().withHeaders(Accept(MediaTypes.`text/plain`)) ~> HttpService.sealRoute(route) ~> check {
        status === StatusCodes.NotAcceptable
      }
    }
  }

  "the redirect directive" should {
    "produce proper 'Found' redirections" in {
      Get() ~> {
        redirect("/foo", Found)
      } ~> check {
        response === HttpResponse(
          status = 302,
          entity = HttpEntity(`text/html`, "The requested resource temporarily resides under <a href=\"/foo\">this URI</a>."),
          headers = Location("/foo") :: Nil)
      }
    }
    "produce proper 'NotModified' redirections" in {
      Get() ~> {
        redirect("/foo", NotModified)
      } ~> check { response === HttpResponse(304, headers = Location("/foo") :: Nil) }
    }
  }

  case class Data(name: String, age: Int)
  object Data {
    import spray.json.DefaultJsonProtocol._
    import spray.httpx.SprayJsonSupport._

    val jsonMarshaller: Marshaller[Data] = jsonFormat2(Data.apply)
    val xmlMarshaller: Marshaller[Data] =
      Marshaller.delegate[Data, xml.NodeSeq](MediaTypes.`text/xml`) { (data: Data) ⇒
        <data><name>{ data.name }</name><age>{ data.age }</age></data>
      }

    implicit val dataMarshaller: Marshaller[Data] =
      Marshaller.oneOf(MediaTypes.`application/json`, MediaTypes.`text/xml`)(jsonMarshaller, xmlMarshaller)
  }
}
