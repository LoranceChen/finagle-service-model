import scala.concurrent.Future

object TestType extends App {
  case class A()
  case class B()
  case class C()
  case class D()
  case class E()
  case class F()
  case class G()
  case class H()

  def filter1 = new Filter[A, B, C, D] {
    override lazy val name: String = "filter1"
    override def apply(v1: A, v2: Service[C, D]): Future[B] = {
      Thread.sleep(1000)
      println("TestType filter1")
      v2(C())
      Future.successful(B())
    }
  }

  def filter2 = new Filter[C, D, E, F] {
    override lazy val name: String = "filter2"
    override def apply(v1: C, v2: Service[E, F]): Future[D] = {
      Thread.sleep(1000)
      println("TestType filter2")
      v2(E())
      Future.successful(D())

    }
  }

  def filter3 = new Filter[E, F, G, H] {
    override lazy val name: String = "filter3"
    override def apply(v1: E, v2: Service[G, H]): Future[F] = {
      Thread.sleep(1000)
      println("TestType filter3")
      v2(G())
      Future.successful(F())
    }
  }

  val service = new Service[G, H] {
    override lazy val name: String = "service1"
    override def apply(v1: G): Future[H] = {
      Thread.sleep(1000)
      println("TestType service")
      Future.successful(H())
    }
  }

  val f1f2svc: Service[A, B] = filter1 andThen
    filter2 andThen
    filter3 andThen
    service

  println("===== do action ===")
  println("result - " + f1f2svc(A()))
  Thread.currentThread().join()
}
