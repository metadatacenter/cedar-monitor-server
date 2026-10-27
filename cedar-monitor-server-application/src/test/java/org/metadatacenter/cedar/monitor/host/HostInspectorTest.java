package org.metadatacenter.cedar.monitor.host;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The host report read from real directories: git checkouts, filesystems and a log tree. */
class HostInspectorTest {

  private static void git(Path directory, String... arguments) throws Exception {
    String[] command = new String[arguments.length + 5];
    command[0] = "git";
    command[1] = "-c";
    command[2] = "user.name=Monitor Test";
    command[3] = "-c";
    command[4] = "user.email=monitor@example.org";
    System.arraycopy(arguments, 0, command, 5, arguments.length);
    Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    assertEquals(0, process.waitFor(), String.join(" ", command) + ": " + output);
  }

  private static Path repository(Path home, String name) throws Exception {
    Path repository = Files.createDirectories(home.resolve(name));
    git(repository, "init", "-q", "-b", "main");
    Files.writeString(repository.resolve("README.md"), "first\n");
    git(repository, "add", "README.md");
    git(repository, "commit", "-q", "-m", "First");
    return repository;
  }

  @Test void noHomeOrAnUnlistableOneHasNoRepositories(@TempDir Path missing) {
    assertEquals(List.of(), HostInspector.repositories(null));
    assertEquals(List.of(), HostInspector.repositories(" "));
    assertEquals(List.of(), HostInspector.repositories(missing.resolve("absent").toString()));
  }

  @Test void eachCheckoutReportsItsBranchCommitEditsAndDistanceFromItsUpstream(@TempDir Path home,
                                                                             @TempDir Path remotes) throws Exception {
    Path edited = repository(home, "alpha");
    Files.writeString(edited.resolve("README.md"), "a hot-patch\n");

    Path origin = Files.createDirectories(remotes.resolve("beta.git"));
    git(origin, "init", "-q", "--bare", "-b", "main");
    Path ahead = repository(home, "beta");
    git(ahead, "remote", "add", "origin", origin.toString());
    git(ahead, "push", "-q", "-u", "origin", "main");
    Files.writeString(ahead.resolve("NOTES.md"), "unpushed\n");
    git(ahead, "add", "NOTES.md");
    git(ahead, "commit", "-q", "-m", "Unpushed");

    Path detached = repository(home, "gamma");
    git(detached, "checkout", "-q", "--detach", "HEAD");

    Path broken = Files.createDirectories(home.resolve("delta"));
    Files.writeString(broken.resolve(".git"), "gitdir: " + remotes.resolve("nowhere") + "\n");
    Files.createDirectories(home.resolve("not-a-checkout"));

    List<HostInspector.RepositoryState> states = HostInspector.repositories(home.toString());
    assertEquals(List.of("alpha", "beta", "delta", "gamma"),
        states.stream().map(HostInspector.RepositoryState::repository).toList());

    HostInspector.RepositoryState alpha = states.get(0);
    assertEquals("main", alpha.branch());
    assertEquals(1, alpha.uncommittedFiles());
    assertNull(alpha.upstream());
    assertNull(alpha.ahead());
    assertEquals(40, alpha.commit().length());
    assertNotNull(alpha.committedAt());
    assertNull(alpha.error());

    HostInspector.RepositoryState beta = states.get(1);
    assertEquals("origin/main", beta.upstream());
    assertEquals(1, beta.ahead());
    assertEquals(0, beta.behind());
    assertEquals(0, beta.uncommittedFiles());

    HostInspector.RepositoryState delta = states.get(2);
    assertNotNull(delta.error());
    assertNull(delta.branch());

    assertEquals("(detached)", states.get(3).branch());
  }

  @Test void onlyReadablePathsReportTheirFilesystem(@TempDir Path directory) {
    List<HostInspector.FilesystemUsage> usages = HostInspector.filesystems(
        Arrays.asList(null, "", directory.resolve("absent").toString(), directory.toString()));
    assertEquals(1, usages.size());
    HostInspector.FilesystemUsage usage = usages.get(0);
    assertEquals(directory.toFile().getAbsolutePath(), usage.path());
    assertTrue(usage.totalBytes() > 0);
    assertTrue(usage.usableBytes() <= usage.totalBytes());
    assertTrue(usage.usedPercent() >= 0 && usage.usedPercent() <= 100);
  }

  @Test void aMissingLogDirectoryIsUnreadableRatherThanEmpty(@TempDir Path directory) throws IOException {
    for (String path : new String[]{null, directory.resolve("absent").toString(),
        Files.writeString(directory.resolve("file.log"), "x").toString()}) {
      Map<String, Object> report = HostInspector.logFiles(path);
      assertEquals(false, report.get("readable"), path);
      assertEquals(0, report.get("fileCount"), path);
      assertEquals(0L, report.get("totalBytes"), path);
    }
  }

  @Test void theLargestLogFilesAreNamedAndEveryFileIsCounted(@TempDir Path logs) throws IOException {
    long total = 0;
    Path nested = Files.createDirectories(logs.resolve("nginx"));
    for (int size = 1; size <= 27; size++) {
      Files.writeString((size % 2 == 0 ? nested : logs).resolve("file-" + size + ".log"), "x".repeat(size));
      total += size;
    }
    Map<String, Object> report = HostInspector.logFiles(logs.toString());
    assertEquals(true, report.get("readable"));
    assertEquals(27, report.get("fileCount"));
    assertEquals(total, report.get("totalBytes"));
    @SuppressWarnings("unchecked")
    List<HostInspector.LogFile> files = (List<HostInspector.LogFile>) report.get("files");
    assertEquals(25, files.size());
    assertEquals(27, files.get(0).sizeBytes());
    assertEquals(3, files.get(24).sizeBytes());
    assertEquals(0, files.get(0).ageDays());
  }

  @Test void aLogTreeThisProcessCannotEnterIsUnreadableWithTheReason(@TempDir Path logs) throws IOException {
    Path locked = Files.createDirectories(logs.resolve("locked"));
    Files.writeString(locked.resolve("secret.log"), "x");
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
    try {
      Assumptions.assumeFalse(locked.toFile().canRead(), "the process can read anything, as root can");
      Map<String, Object> report = HostInspector.logFiles(logs.toString());
      assertEquals(false, report.get("readable"));
      assertNotNull(report.get("error"));
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }
}
