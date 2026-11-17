#!/usr/bin/env python3
"""Compile the exact androidTest core, then run bounded Android-free experiments."""
if __name__ == "__main__":
    from run_with_timeout import guard_script
    guard_script(__file__)

import json
import contextlib
import io
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import public_test_evidence as public

ROOT = Path(__file__).resolve().parent.parent
FIXTURE = r'''
package juloo.keyboard2;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;

public final class ProgressEvidenceHostTest {
  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  static void await(CountDownLatch latch) throws Exception {
    check(latch.await(3, TimeUnit.SECONDS), "fixture deadline");
  }
  static class Clock implements ProgressEvidence.Clock {
    volatile long time = 1000;
    public long now() { return time; }
    public void sleep(long ms) { time += ms; }
  }
  static class Sink implements ProgressEvidence.Sink {
    final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    boolean closed;
    public void open(ProgressEvidence.Run run) throws Exception {}
    public void write(byte[] record) throws Exception { lines.add(new String(record, StandardCharsets.UTF_8)); }
    public void close() throws Exception { closed = true; }
    String text() { return String.join("", lines); }
  }
  static ProgressEvidence make(Clock c, ProgressEvidence.Poster p, Sink s) {
    return new ProgressEvidence(ProgressEvidence.Run.INTERACTIONS, c, p, s);
  }
  static CountDownLatch launch(ProgressEvidence e) {
    CountDownLatch done = new CountDownLatch(1);
    Thread worker = new Thread(() -> { try { e.work(); } finally { done.countDown(); } });
    worker.setDaemon(true); worker.start(); return done;
  }
  static void emit(Sink sink) {
    check(sink.lines.size() <= 96, "record limit");
    check(sink.text().getBytes(StandardCharsets.UTF_8).length <= 65536, "UTF-8 limit");
    System.out.print(sink.text());
  }
  public static void main(String[] args) throws Exception {
    String name = args[0]; Clock c = new Clock(); Sink s = new Sink();
    List<Long> calls = new ArrayList<>(); List<Runnable> callbacks = new ArrayList<>();
    ProgressEvidence.Guard guard = new ProgressEvidence.Guard();
    switch (name) {
      case "early_ack": {
        ProgressEvidence e = make(c, r -> { calls.add(c.now()); r.run(); return true; }, s);
        e.scope(ProgressEvidence.Scope.SYMBOL);
        e.mark(ProgressEvidence.Stage.CATEGORY_LOOKUP_ENTER, 17, 1, 0, 11);
        e.work();
        check(calls.size() == 83, "83 attempts before 840s");
        for (int i=0; i<calls.size(); i++) check(calls.get(i) == 11000 + i*10000L, "nominal cadence");
        check(s.text().contains("\"ack\":83"), "early ack retained");
        check(s.text().contains("\"call\":17,\"category\":1,\"page\":0,\"attempt\":11"), "snapshot fields");
        check(s.lines.size() == 86 && s.closed, "header and exit budget"); emit(s); break;
      }
      case "pending_and_late_owner": {
        ProgressEvidence e = make(c, r -> { callbacks.add(r); return true; }, s);
        check(guard.claim(e), "first owner"); e.mark(ProgressEvidence.Stage.CATEGORY_ENTER, 2, 1, 0, -1);
        e.work(); check(callbacks.size() == 1, "one unacknowledged request");
        Sink nextSink = new Sink();
        ProgressEvidence next = make(c, r -> { r.run(); return true; }, nextSink);
        check(!guard.claim(next), "unresolved callback blocks replacement after exit");
        callbacks.get(0).run(); check(guard.claim(next), "late ack releases exited owner");
        next.mark(ProgressEvidence.Stage.FN_ENTER); next.close(); next.work();
        String old = s.text(), newer = nextSink.text();
        callbacks.get(0).run(); e.mark(ProgressEvidence.Stage.TOUCH_ENTER);
        check(newer.equals(nextSink.text()) && old.equals(s.text()), "former owner cannot corrupt new artifact");
        check(!newer.contains("CATEGORY_ENTER"), "snapshots stay with owner"); emit(s); break;
      }
      case "concurrent_ack": {
        CountDownLatch sampled = new CountDownLatch(1), resume = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        Sink active = new Sink() {
          private boolean held;
          public void write(byte[] data) throws Exception {
            super.write(data);
            if (!held && new String(data, StandardCharsets.UTF_8).contains("\"event\":\"pending\"")) {
              held = true; sampled.countDown(); await(resume);
            }
          }
        };
        ProgressEvidence[] owner = new ProgressEvidence[1];
        ProgressEvidence e = make(c, r -> {
          callbacks.add(r);
          if (callbacks.size() == 2) { r.run(); owner[0].close(); }
          return true;
        }, active);
        owner[0] = e; guard.claim(e); CountDownLatch done = launch(e); await(sampled);
        check(callbacks.size() == 1, "one pending callback before asynchronous ack");
        Thread acknowledger = new Thread(() -> { callbacks.get(0).run(); published.countDown(); });
        acknowledger.setDaemon(true); acknowledger.start(); await(published);
        check(done.getCount() == 1, "ack published while worker remains active");
        ProgressEvidence next = make(c, r -> true, new Sink());
        check(!guard.claim(next), "ack does not retire an active owner");
        resume.countDown(); await(done);
        check(callbacks.size() == 2 && active.text().contains("\"request\":2,\"ack\":1"),
              "worker observes concurrent ack before reserving next request");
        check(guard.claim(next), "exited owner with acknowledged requests can retire"); emit(active); break;
      }
      case "rejection": {
        ProgressEvidence e = make(c, r -> { calls.add(c.now()); return false; }, s); e.work();
        check(calls.size() == 83 && s.text().contains("\"previous_post\":\"rejected\""), "rejected requests release reservation");
        check(guard.claim(e), "claim terminated owner");
        check(guard.claim(make(c, r -> true, new Sink())), "rejection leaves no callback"); emit(s); break;
      }
      case "throwing_post": {
        ProgressEvidence e = make(c, r -> { callbacks.add(r); throw new IllegalStateException("fixture"); }, s);
        guard.claim(e); e.work(); check(callbacks.size() == 1, "throw could already enqueue");
        check(s.text().contains("threw_outcome_unknown"), "throw recorded without message text");
        ProgressEvidence next = make(c, r -> true, new Sink());
        check(!guard.claim(next), "throw preserves unresolved reservation");
        callbacks.get(0).run(); check(guard.claim(next), "throw followed by late ack"); emit(s); break;
      }
      case "ack_then_throw": {
        ProgressEvidence e = make(c, r -> { calls.add(c.now()); r.run(); throw new IllegalStateException(); }, s);
        e.work(); check(calls.size() == 83, "early ack survives throwing post"); emit(s); break;
      }
      case "blocked_post": {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ProgressEvidence e = make(c, r -> { callbacks.add(r); entered.countDown(); await(release); return true; }, s);
        guard.claim(e); CountDownLatch done = launch(e); await(entered);
        check(s.text().contains("reserved_before_post"), "snapshot flushed before stalled post");
        e.close(); check(done.getCount() == 1, "close does not wait or interrupt post");
        ProgressEvidence next = make(c, r -> true, new Sink()); next.mark(ProgressEvidence.Stage.FN_ENTER);
        check(!guard.claim(next), "stalled owner guard");
        release.countDown(); await(done); check(!guard.claim(next), "exit still has unresolved callback");
        callbacks.get(0).run(); check(guard.claim(next), "late callback retires old owner"); emit(s); break;
      }
      case "blocked_open": case "blocked_write": case "blocked_close": {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Sink blocked = new Sink() {
          void block() throws Exception { entered.countDown(); await(release); }
          public void open(ProgressEvidence.Run run) throws Exception { if (name.equals("blocked_open")) block(); }
          public void write(byte[] data) throws Exception {
            super.write(data);
            if (name.equals("blocked_write") && new String(data, StandardCharsets.UTF_8).contains("reserved_before_post")) block();
          }
          public void close() throws Exception { if (name.equals("blocked_close")) block(); super.close(); }
        };
        ProgressEvidence e = make(c, r -> { calls.add(c.now()); r.run(); return true; }, blocked);
        guard.claim(e); CountDownLatch done = launch(e); await(entered);
        e.close(); check(done.getCount() == 1, "nonblocking close while sink stalls");
        ProgressEvidence next = make(c, r -> true, new Sink());
        check(!guard.claim(next), "no replacement during blocked I/O");
        release.countDown(); await(done); check(guard.claim(next), "reuse only after recorded exit and close");
        if (!name.equals("blocked_close")) check(calls.isEmpty(), "stop rechecked after blocked sink");
        emit(blocked); break;
      }
      case "sink_failures": {
        for (String operation : Arrays.asList("open", "write", "close")) {
          Sink broken = new Sink() {
            public void open(ProgressEvidence.Run run) { if (operation.equals("open")) throw new AssertionError(); }
            public void write(byte[] b) throws Exception { if (operation.equals("write")) throw new AssertionError(); super.write(b); }
            public void close() { if (operation.equals("close")) throw new AssertionError(); }
          };
          ProgressEvidence e = make(c, r -> { r.run(); return true; }, broken);
          ProgressEvidence.Guard g = new ProgressEvidence.Guard(); g.claim(e); e.work();
          check(!g.claim(make(c, r -> true, new Sink())), "no replacement without successful exit and close: " + operation);
        }
        break;
      }
      case "stop_deadline_and_skip": {
        ProgressEvidence stopped = make(c, r -> { throw new AssertionError("post after stop"); }, s);
        stopped.close(); stopped.work(); check(s.text().contains("exit_stop"), "initial stop");
        Sink slow = new Sink() {
          public void write(byte[] b) throws Exception {
            super.write(b);
            if (new String(b, StandardCharsets.UTF_8).contains("reserved_before_post")) c.time += 25000;
          }
        };
        ProgressEvidence e = make(c, r -> { calls.add(c.now()); r.run(); c.time += 17000; return true; }, slow);
        e.work();
        for (int i=1; i<calls.size(); i++) check(calls.get(i)-calls.get(i-1)>=10000, "skip missed intervals");
        check(slow.text().contains("exit_deadline"), "deadline despite observer delay");
        Clock late = new Clock(); Sink over = new Sink() {
          public void open(ProgressEvidence.Run r) { late.time += 900000; }
        };
        make(late, r -> { throw new AssertionError("post after late open"); }, over).work();
        check(over.lines.size()==3, "no attempt after open passes deadline"); emit(slow); break;
      }
      case "limits": {
        // Exercise the real UTF-8/header/newline accounting, including oversized records.
        Method write = ProgressEvidence.class.getDeclaredMethod("write", String.class); write.setAccessible(true);
        ProgressEvidence e = make(c, r -> true, s);
        for (int i=0; i<150; i++) write.invoke(e, "{}");
        check(s.lines.size()==96 && s.text().getBytes(StandardCharsets.UTF_8).length==288, "record cap includes newlines");
        Sink bytes = new Sink(); e = make(c, r -> true, bytes);
        check((Boolean)write.invoke(e, "{}"), "header-equivalent counted");
        String unicode = String.join("", Collections.nCopies(32766, "é"));
        check((Boolean)write.invoke(e, unicode), "UTF-8 cap exact fit including header and newline");
        check(!(Boolean)write.invoke(e, ""), "byte cap denies following newline");
        check(!(Boolean)write.invoke(make(c, r -> true, new Sink()), unicode+unicode), "single oversized record rejected");
        check(bytes.text().getBytes(StandardCharsets.UTF_8).length==65536, "exact UTF-8 bound"); break;
      }
      case "original_exception_and_markers": {
        ProgressEvidence.Clock broken = new ProgressEvidence.Clock() {
          public long now() { throw new AssertionError("clock"); }
          public void sleep(long ms) {}
        };
        ProgressEvidence e = new ProgressEvidence(ProgressEvidence.Run.REOPEN, broken, r -> true, s);
        AssertionError original = new AssertionError("acceptance sentinel");
        try {
          try { e.mark(ProgressEvidence.Stage.START); throw original; }
          finally { e.mark(ProgressEvidence.Stage.FINALLY_ENTER); e.close(); }
        } catch (AssertionError observed) { check(observed==original, "same original exception instance"); }
        ProgressEvidence valid = make(c, r -> { r.run(); return true; }, s);
        valid.mark(ProgressEvidence.Stage.CATEGORY_ENTER, 9, 1, 0, 3);
        valid.mark(ProgressEvidence.Stage.TOUCH_ENTER, 257, 1, 0, 3);
        valid.mark(ProgressEvidence.Stage.TOUCH_ENTER, 9, 8, 0, 3);
        valid.mark(ProgressEvidence.Stage.TOUCH_ENTER, 9, 1, 32, 3);
        valid.mark(ProgressEvidence.Stage.TOUCH_ENTER, 9, 1, 0, 32);
        valid.close(); valid.work();
        check(s.text().contains("CATEGORY_ENTER")&&!s.text().contains("TOUCH_ENTER"), "bounded marker values"); emit(s); break;
      }
      case "failure_latched_before_cleanup": {
        ProgressEvidence e = new ProgressEvidence(ProgressEvidence.Run.REBOOT, c, r -> true, s);
        e.scope(ProgressEvidence.Scope.FIRST_INSTALL);
        e.mark(ProgressEvidence.Stage.BACK_RETURN, 1, 0, -1, -1);
        AssertionError original = new AssertionError("PRIVATE filename /prefs/user.db Thread[secret] Intent{secret}");
        Throwable primary = null;
        try {
          try { throw original; }
          catch (AssertionError observed) {
            primary = observed; e.failed(ProgressEvidence.FailurePhase.FLOW, ProgressEvidence.kind(observed)); throw observed;
          } finally {
            ProgressEvidence.cleanup(primary, () -> {
              e.mark(ProgressEvidence.Stage.FINALLY_ENTER);
              e.failed(ProgressEvidence.FailurePhase.CLEANUP, ProgressEvidence.FailureKind.IO);
              throw new java.io.IOException("PRIVATE cleanup filename");
            });
          }
        } catch (AssertionError observed) { check(observed == original, "same primary object after cleanup"); }
        e.close(); e.work();
        check(s.text().contains("\"phase\":\"FLOW\",\"kind\":\"ASSERTION\""), "first failure kind");
        check(s.text().contains("\"stage\":\"BACK_RETURN\",\"seq\":1"), "pre-teardown boundary retained");
        check(!s.text().contains("PRIVATE") && !s.text().contains("Intent{"), "no exception text"); emit(s); break;
      }
      case "failure_recorder_unavailable": {
        class BrokenClock extends Clock {
          boolean broken;
          public long now() { if (broken) throw new AssertionError("PRIVATE clock"); return super.now(); }
        }
        BrokenClock broken = new BrokenClock();
        ProgressEvidence e = make(broken, r -> true, s);
        e.mark(ProgressEvidence.Stage.BACK_RETURN);
        AssertionError original = new AssertionError("PRIVATE original");
        try {
          try { throw original; }
          catch (AssertionError observed) {
            broken.broken = true;
            e.failed(ProgressEvidence.FailurePhase.FLOW, ProgressEvidence.kind(observed));
            broken.broken = false;
            throw observed;
          } finally {
            ProgressEvidence.cleanup(original, () -> { throw new AssertionError("PRIVATE cleanup"); });
          }
        } catch (AssertionError observed) { check(observed == original, "recorder failure preserves original"); }
        e.failed(ProgressEvidence.FailurePhase.CLEANUP, ProgressEvidence.FailureKind.IO);
        e.close(); e.work();
        check(!s.text().contains("\"status\":\"observed\""), "later cleanup cannot replace failed recorder attempt");
        check(s.text().contains("\"failure\":{\"status\":\"unavailable\"}"), "explicit unavailable"); emit(s); break;
      }
      case "cleanup_first_failure": {
        ProgressEvidence e = make(c, r -> true, s);
        e.mark(ProgressEvidence.Stage.EVIDENCE_ENTER);
        java.io.IOException original = new java.io.IOException("PRIVATE filename");
        try {
          ProgressEvidence.cleanup(null, () -> { throw original; });
          throw new AssertionError("cleanup failure disappeared");
        } catch (java.io.IOException observed) {
          check(observed == original, "first cleanup failure remains same object");
          e.failed(ProgressEvidence.FailurePhase.CLEANUP, ProgressEvidence.kind(observed));
        }
        e.failed(ProgressEvidence.FailurePhase.FLOW, ProgressEvidence.FailureKind.OTHER);
        e.close(); e.work();
        check(s.text().contains("\"phase\":\"CLEANUP\",\"kind\":\"IO\""), "cleanup kind retained"); emit(s); break;
      }
      case "failure_kinds_and_unobserved": {
        check(ProgressEvidence.kind(new AssertionError()) == ProgressEvidence.FailureKind.ASSERTION, "assertion kind");
        check(ProgressEvidence.kind(new TimeoutException()) == ProgressEvidence.FailureKind.TIMEOUT, "timeout kind");
        check(ProgressEvidence.kind(new InterruptedException()) == ProgressEvidence.FailureKind.INTERRUPTED, "interrupt kind");
        check(ProgressEvidence.kind(new java.io.IOException()) == ProgressEvidence.FailureKind.IO, "io kind");
        Throwable hostile = new RuntimeException() {
          @Override public String getMessage() { throw new AssertionError("message inspected"); }
          @Override public String toString() { throw new AssertionError("text inspected"); }
        };
        check(ProgressEvidence.kind(hostile) == ProgressEvidence.FailureKind.OTHER, "unknown class, no text inspection");
        ProgressEvidence e = make(c, r -> true, s);
        e.failed(ProgressEvidence.FailurePhase.FLOW, null);
        e.failed(ProgressEvidence.FailurePhase.CLEANUP, ProgressEvidence.FailureKind.OTHER);
        e.close(); e.work(); check(!s.text().contains("\"status\":\"observed\""), "unobserved stays unavailable"); emit(s); break;
      }
      case "failure_record_caps": {
        ProgressEvidence e = make(c, r -> { r.run(); return true; }, s);
        e.mark(ProgressEvidence.Stage.CATEGORY_SCROLL_LOOKUP_RETURN, 256, 7, 31, 31);
        e.failed(ProgressEvidence.FailurePhase.FLOW, ProgressEvidence.FailureKind.AUTOMATION);
        e.work(); check(s.lines.size() == 86, "failure metadata retains normal record bound"); emit(s); break;
      }
      default: throw new AssertionError(name);
    }
    System.out.println("PASS " + name);
  }
}
'''


class ProgressEvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="miancak-progress-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        java_home = os.environ.get("JAVA_HOME")
        cls.javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
        cls.java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
        if not cls.javac or not cls.java:
            raise RuntimeError("A JDK with javac and java is required; no test was skipped")
        core = ROOT / "androidTest/juloo/keyboard2/ProgressEvidence.java"
        # Protocol enums must remain synchronized with the strict public reader.
        for enum, expected in [('Scope', public.SCOPES), ('Stage', public.STAGES),
                               ('FailurePhase', public.FAILURE_PHASES), ('FailureKind', public.FAILURE_KINDS)]:
            body = re.search(r'enum ' + enum + r'\s*\{([^}]+)\}', core.read_text()).group(1)
            actual = set(re.findall(r'\b[A-Z][A-Z_]*\b', body))
            if actual != expected:
                raise AssertionError('Producer/exporter enum mismatch: ' + enum)
        fixture = cls.directory / "ProgressEvidenceHostTest.java"
        fixture.write_text(FIXTURE)
        result = subprocess.run([cls.javac, "--release", "8", "-d", str(cls.directory),
                                 str(core), str(fixture)],
                                capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    def scenario(self, name):
        result = subprocess.run([self.java, "-cp", str(self.directory),
                                 "juloo.keyboard2.ProgressEvidenceHostTest", name],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(result.stdout.endswith("PASS " + name + "\n"), result.stdout)
        records = result.stdout.splitlines()[:-1]
        self.assertLessEqual(len(records), 96)
        self.assertLessEqual(sum(len((line + "\n").encode("utf-8")) for line in records), 65536)
        for line in records:
            self.assertIsInstance(json.loads(line), dict)
        if records:
            # Roundtrip actual Java bytes through the public exporter, including
            # schema-2 headers and first-failure snapshots, not handwritten rows.
            parsed = [json.loads(line) for line in records]
            identity = (parsed[0]['scenario'], parsed[0]['phase'])
            filename = next(n for n, pair in public.RUNS.items() if pair == identity)
            with tempfile.TemporaryDirectory(prefix='progress-export-') as directory:
                root = Path(directory); target = root/'test-results/device'/filename
                target.parent.mkdir(parents=True); target.write_text('\n'.join(records)+'\n')
                with contextlib.redirect_stdout(io.StringIO()):
                    public.stage(root, identity[0] if identity[0] == 'first-install' else 'interaction',
                                 'failure', 'a'*40)
                summary = json.loads((root/'public-test-results/summary.json').read_text())
                self.assertEqual(2, summary['schema'])
                self.assertEqual('available', summary['heartbeat'][filename])
                failure = summary['failure_evidence'][filename]
                if name in ('failure_latched_before_cleanup','cleanup_first_failure','failure_record_caps'):
                    self.assertEqual('observed', failure['status'])
                else:
                    self.assertEqual({'status':'unavailable'}, failure)
                if name == 'failure_latched_before_cleanup':
                    self.assertEqual(('FLOW','ASSERTION','BACK_RETURN'),
                                     (failure['phase'],failure['kind'],failure['stage']))
                text = (root/'public-test-results/heartbeat.jsonl').read_text()
                self.assertEqual(parsed, [json.loads(line)['record'] for line in text.splitlines()])
                self.assertNotIn('PRIVATE', text)
                self.assertNotIn('Intent{', text)


def install_scenario(name):
    setattr(ProgressEvidenceTests, "test_" + name, lambda self: self.scenario(name))


for _name in ("early_ack", "pending_and_late_owner", "concurrent_ack", "rejection", "throwing_post", "ack_then_throw",
              "blocked_post", "blocked_open", "blocked_write", "blocked_close", "sink_failures",
              "stop_deadline_and_skip", "limits", "original_exception_and_markers",
              "failure_latched_before_cleanup", "failure_recorder_unavailable", "cleanup_first_failure",
              "failure_kinds_and_unobserved", "failure_record_caps"):
    install_scenario(_name)

if __name__ == "__main__":
    unittest.main()
