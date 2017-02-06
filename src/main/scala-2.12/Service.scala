import scala.concurrent.{Future, Promise, blocking}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Module same as finagle's Service
  */
trait Module[-Req, +Rsp] extends (Req => Future[Rsp])

/**
  *           (*   Service   *)
  * [ReqIn -> (ReqOut -> RepIn) -> RepOut]
  *
  */
trait Filter[-ReqIn, +RepOut, +ReqOut, -RepIn] extends ((ReqIn, Module[ReqOut, RepIn]) => Future[RepOut]) {
  /**
    * final def andThen(next: ThriftFilter) = new ThriftFilter {
    override def apply[T, Rep](
      request: ThriftRequest[T],
      svc: Service[ThriftRequest[T], Rep]
    ): Future[Rep] = self.apply(request, next.toFilter[T, Rep].andThen(svc))
  }

    *
    * @return
    *           (*   Service   *)
    * [ReqIn -> (Req2 -> Rep2) -> RepOut]
    *
    */
  def andThen[Req2, Rep2](next: Filter[ReqOut, RepIn, Req2, Rep2]) = new Filter[ReqIn, RepOut, Req2, Rep2] {
    override def apply(request: ReqIn, module: Module[Req2, Rep2]) = {
      val mdl: Module[ReqOut, RepIn] = new Module[ReqOut, RepIn] {
        override def apply(v1: ReqOut) = {
          try {
            next(v1, module)
          } catch {
            case NonFatal(e) => Future.failed(e)
          }
        }
      }
      Filter.this.apply(request, mdl)
    }
  }

  def andThen(module: Module[ReqOut, RepIn]): Module[ReqIn, RepOut] = {
    new Module[ReqIn, RepOut] {
      def apply(request: ReqIn) = Filter.this.apply(request, module)
    }
  }
}

class TimeOutException extends RuntimeException

class TimeoutFilter[Req, Rep]( exception: TimeOutException,
                               timeout: Duration)
  extends Filter[Req, Rep, Req, Rep]
{

  def apply(request: Req, service: Module[Req, Rep]): Future[Rep] = {
    val res = service(request)

    //todo put Timeout Future to TimerTask which only use one thread.
    new FutureEx(res).withTimeout(timeout).recover{
      case exception @ (_: FutureTimeoutException | _: FutureTimeoutNotOccur) =>
        ???
    }
  }

  sealed class FutureTimeoutException extends RuntimeException
  sealed class FutureTimeoutNotOccur extends RuntimeException

  class FutureEx[T](f: Future[T]) {
    def withTimeout(ms: Long = 2000): Future[T] = Future.firstCompletedOf(List(f, {
      val p = Promise[T]
      Future {
        blocking(Thread.sleep(ms))
        if(!f.isCompleted) {
          p.tryFailure(new FutureTimeoutException)
        } else {
          p.tryFailure(new FutureTimeoutNotOccur)
        }
      }
      p.future
    }))

    def withTimeout(duration: Duration): Future[T] = withTimeout(duration.toMillis)
  }

}

case class AuthService(rst: String) {
  def auth(req: HttpReq): Future[String] = ??? //result can be other data type
}

case class HttpReq()
case class HttpRsp()
case class AuthHttpReq(rst: String)

class RequireAuthentication(authService: AuthService)
  extends Filter[HttpReq, HttpRsp, AuthHttpReq, HttpRsp] {
  def apply(
             req: HttpReq,
             service: Module[AuthHttpReq, HttpRsp]
           ) = {
    authService.auth(req) flatMap {
      case "OK" =>
        service(AuthHttpReq("OK"))
      case ar =>
        Future.failed(
          new Exception())
    }
  }
}

trait Test {
  val s: Filter[HttpReq, HttpRsp, HttpReq, HttpRsp] = new TimeoutFilter[HttpReq, HttpRsp](new TimeOutException(), Duration.apply(""))
  val ss: Filter[HttpReq, HttpRsp, AuthHttpReq, HttpRsp] = new RequireAuthentication(AuthService("OK"))
  val authFilter: Filter[HttpReq, HttpRsp, AuthHttpReq, HttpRsp] = ???
  def timeoutfilter[Req, Rep]: Filter[Req, Rep, Req, Rep] = ???
  val serviceRequiringAuth: Module[AuthHttpReq, HttpRsp]

  //combine filters
  val authenticateAndTimedOut: Filter[HttpReq, HttpRsp, AuthHttpReq, HttpRsp] =
    s.andThen(authFilter)

  //run service
  val authenticatedTimedOutService: Module[HttpReq, HttpRsp] =
    authenticateAndTimedOut andThen serviceRequiringAuth
}