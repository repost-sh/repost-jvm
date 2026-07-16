package sh.repost.conformance.transport;

public final class RawFixtureMain {
  private RawFixtureMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 2 || !"--group".equals(args[0])) {
      System.err.println("usage: RawFixtureMain --group <groupId>");
      System.exit(2);
    }
    JvmRawFixtureRunner runner = new JvmRawFixtureRunner(args[1]);
    runner.start();
    runner.awaitClosed();
  }
}
