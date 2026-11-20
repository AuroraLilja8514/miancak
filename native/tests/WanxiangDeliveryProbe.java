package juloo.keyboard2.input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/** Host JNI probe for the exact APK resource plan and separately verified original files. */
public final class WanxiangDeliveryProbe
{
  public static void main(String[] args) throws Exception
  {
    Path assets = Paths.get(args[0]);
    Path base = Paths.get(args[1]), model = Paths.get(args[2]);
    Path root = Paths.get(args[3]);
    if (!Files.isDirectory(root)) throw new AssertionError("Use a new empty test directory");
    try (Stream<Path> entries = Files.list(root))
    { require(entries.findAny().isEmpty(), "Test directory was not empty"); }
    byte[] manifest = Files.readAllBytes(assets.resolve("manifest.json"));
    byte[] plan = Files.readAllBytes(assets.resolve("delivery.json"));
    String digest = RimeData.hex(RimeData.digest().digest(manifest));
    RimeData.Paths paths = new RimeData.Paths(root.resolve("shared-" + digest).toFile(), root.resolve("user").toFile());
    WanxiangInstall install = new WanxiangInstall(paths, plan, manifest);
    require(!install.installed(), "Resources present before setup");
    long[] transferred = {0};
    try (WanxiangInstall.Staged stage = install.prepare(spec -> Files.newInputStream(spec == install.base ? base : model),
        name -> Files.newInputStream(assets.resolve(name)), bytes -> transferred[0] = bytes, () -> {}))
    {
      require(stage != null && !paths.shared.exists(), "Resources became visible before native validation");
      stage.activate(WanxiangInstall::verifyNative);
    }
    require(transferred[0] == install.transferBytes, "Unexpected transfer total");
    require(!paths.user.exists(), "Setup used the real user directory");
    require(install.installed(), "Installed bytes did not verify");
    require(!root.resolve(".wanxiang-stage").toFile().exists(), "Staging was not cleaned");
    WanxiangInstall reopened = new WanxiangInstall(paths, plan, manifest);
    require(reopened.prepare(spec -> { throw new AssertionError("Repeated transfer"); },
        name -> { throw new AssertionError("Repeated asset copy"); }, n -> {}, () -> {}) == null, "Installed assets not reused");

    Files.createDirectory(paths.user.toPath());
    try (RimeEngine engine = new RimeEngine(paths))
    {
      engine.setLearning(false);
      EngineResult first = engine.processText("shi");
      EngineResult larger = engine.candidates(first.candidates.size() + 40);
      require(larger.candidates.size() > first.candidates.size() && larger.extendsCandidatePrefix(first),
          "The expanded prefix lost native choice identity");
      engine.clear();
      EngineResult choices = engine.processText("nihaoshijie");
      int selected = choices.candidates.indexOf("你好");
      require(selected >= 0, "Installed grammar and schema did not provide the expected partial choice");
      EngineResult partial = engine.select(selected);
      require(partial.composing && partial.committed.isEmpty(), "Partial selection committed too early");
      require(engine.releaseToEnglish().committed.equals("你好shijie"), "Shift changed explicitly selected text or literal tail");
      require(engine.releaseToEnglish().committed.isEmpty(), "Shift duplicated text");
    }
    try (Stream<Path> files = Files.walk(paths.build.toPath()))
    {
      long compiled = 0;
      for (Path file : (Iterable<Path>)files.filter(Files::isRegularFile)::iterator) compiled += Files.size(file);
      require(compiled > 0, "Installed native compilation missing");
      System.out.println("PASS verified model and Base; native probe; offline reopen; no redundant transfer; partial Shift handoff");
      System.out.println("resource_bytes=" + transferred[0] + " compiled_bytes=" + compiled + " manifest_sha256=" + digest);
    }
  }

  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
