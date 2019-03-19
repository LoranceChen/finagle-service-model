import scala.concurrent.Future

object SimpleTest extends App {
  case class A()

  case class Filter1() extends Filter[A, A, A, A] {
    override def apply(v1: A, v2: Service[A, A]): Future[A] = {
      Thread.sleep(1000)

      println(System.currentTimeMillis + "filter1")
      v2(v1)
      Future.successful(A())
    }
  }

  def filter1 = Filter1()

  case class Filter2() extends Filter[A, A, A, A] {
    override def apply(v1: A, v2: Service[A, A]): Future[A] = {
      Thread.sleep(1000)

      println(System.currentTimeMillis + "filter2")
      //      v2(v1)
      Future.successful(A())
    }
  }

  def filter2 = Filter2()

  case class Filter3() extends Filter[A, A, A, A] {
    override def apply(v1: A, v2: Service[A, A]): Future[A] = {
      Thread.sleep(1000)

      println(System.currentTimeMillis + "filter3")
      v2(v1)
      Future.successful(A())
    }
  }

  def filter3 = Filter3()

  case class Service1() extends Service[A, A] {
    override def apply(v1: A): Future[A] = {
      Thread.sleep(1000)

      println(System.currentTimeMillis + "service")
      Future.successful(A())
    }
  }

  def service = Service1()

  val rst = filter1 andThen
    filter2 andThen
    filter3 andThen
    filter3 andThen service

  println(rst(A()))

  Thread.currentThread().join()
}
