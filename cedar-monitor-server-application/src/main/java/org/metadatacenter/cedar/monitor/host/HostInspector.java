package org.metadatacenter.cedar.monitor.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * What the box this process is running on looks like: which commit each CEDAR repository is sitting
 * on, and how much room is left for the logs.
 *
 * <p>Both are facts about one host, and which host that is depends on how CEDAR was deployed.
 * Production runs native — every service is a JVM on the application server, the repositories are
 * checked out at {@code CEDAR_HOME} on that same box, and this is the report for all of it. Under
 * Docker each service is its own container and this describes the monitoring container alone. The
 * report says which of the two it is looking at rather than leaving the reader to assume.
 *
 * <p>The git state is read by running git, not by parsing {@code .git} by hand. Branch, commit and
 * modified-file count come back from one {@code status --porcelain=v2 --branch} per repository, and
 * the uncommitted count is the point of it: production carries emergency hot-patches applied
 * directly on the box as working-tree edits, and a deploy that pulls over them loses them silently.
 * The paths passed to git are the directory listing of {@code CEDAR_HOME} and never anything a
 * caller supplied.
 */
public final class HostInspector {

  private static final Logger log = LoggerFactory.getLogger(HostInspector.class);

  /** Long enough for git to answer on a large repository, short enough that a wedged call cannot hold the page. */
  private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

  /** Repositories are read concurrently; the bound keeps a scan of forty of them off the box's whole CPU. */
  private static final int SCAN_THREADS = 8;

  /** How many of the largest log files to name. Enough to spot the one that is eating the disk. */
  private static final int LARGEST_LOG_FILES = 25;

  private HostInspector() {
  }

  // ---- git ---------------------------------------------------------------------------------------

  /**
   * One repository's state.
   *
   * @param repository       the directory name under CEDAR_HOME
   * @param branch           the checked-out branch, or {@code (detached)} when HEAD is not on one
   * @param commit           the full commit SHA at HEAD
   * @param committedAt      when that commit was authored, ISO-8601
   * @param uncommittedFiles how many tracked files differ from HEAD — non-zero means a hot-patch or
   *                         work in progress that a pull would destroy
   * @param ahead            commits on this branch that its upstream does not have
   * @param behind           commits on the upstream that this branch does not have
   * @param upstream         the upstream branch, where one is configured
   * @param error            what went wrong, when the repository could not be read
   */
  public record RepositoryState(
      String repository,
      String branch,
      String commit,
      String committedAt,
      Integer uncommittedFiles,
      Integer ahead,
      Integer behind,
      String upstream,
      String error) {
  }

  /** Every git repository directly under {@code cedarHome}, read in parallel, sorted by name. */
  public static List<RepositoryState> repositories(String cedarHome) {
    if (cedarHome == null || cedarHome.isBlank()) {
      return List.of();
    }
    File root = new File(cedarHome);
    File[] children = root.listFiles(child -> child.isDirectory() && new File(child, ".git").exists());
    if (children == null) {
      return List.of();
    }

    ExecutorService pool = Executors.newFixedThreadPool(Math.min(SCAN_THREADS, Math.max(1, children.length)));
    try {
      List<Future<RepositoryState>> futures = new ArrayList<>();
      for (File repository : children) {
        futures.add(pool.submit(() -> readRepository(repository)));
      }
      List<RepositoryState> states = new ArrayList<>();
      for (Future<RepositoryState> future : futures) {
        try {
          states.add(future.get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (ExecutionException e) {
          log.warn("A repository could not be read", e.getCause());
        }
      }
      states.sort(Comparator.comparing(RepositoryState::repository));
      return states;
    } finally {
      pool.shutdownNow();
    }
  }

  private static RepositoryState readRepository(File repository) {
    String name = repository.getName();
    try {
      // --untracked-files=no on purpose: build output and node_modules are untracked by the
      // hundreds of thousands, listing them would dominate the runtime, and a hot-patch is an edit
      // to a tracked file.
      List<String> status = run(repository, "git", "status", "--porcelain=v2", "--branch", "--untracked-files=no");

      String branch = null;
      String commit = null;
      String upstream = null;
      Integer ahead = null;
      Integer behind = null;
      int changed = 0;

      for (String line : status) {
        if (line.startsWith("# branch.head ")) {
          branch = line.substring("# branch.head ".length()).trim();
        } else if (line.startsWith("# branch.oid ")) {
          commit = line.substring("# branch.oid ".length()).trim();
        } else if (line.startsWith("# branch.upstream ")) {
          upstream = line.substring("# branch.upstream ".length()).trim();
        } else if (line.startsWith("# branch.ab ")) {
          String[] parts = line.substring("# branch.ab ".length()).trim().split("\\s+");
          if (parts.length == 2) {
            ahead = Integer.parseInt(parts[0].replace("+", ""));
            behind = Math.abs(Integer.parseInt(parts[1]));
          }
        } else if (!line.startsWith("#")) {
          changed++;
        }
      }

      // git reports a detached HEAD as the literal "(detached)", which is already what we want to show.
      List<String> committedAt = run(repository, "git", "log", "-1", "--format=%cI");
      String when = committedAt.isEmpty() ? null : committedAt.get(0).trim();

      return new RepositoryState(name, branch, commit, when, changed, ahead, behind, upstream, null);
    } catch (InterruptedException e) {
      // The scan is being shut down. Restore the flag so the pool's shutdownNow is not swallowed
      // here, and report the repository as unread rather than as having no branch.
      Thread.currentThread().interrupt();
      return new RepositoryState(name, null, null, null, null, null, null, null, "interrupted");
    } catch (Exception e) {
      return new RepositoryState(name, null, null, null, null, null, null, null, messageOf(e));
    }
  }

  /**
   * Runs a command in a directory and returns its stdout lines, or throws if it fails or hangs.
   *
   * <p>The timeout is enforced by a watchdog rather than by waiting on the process after reading
   * it. Reading a process's output to EOF blocks until the process closes the stream, so a
   * {@code waitFor} placed after the read can only ever be reached once the process is already
   * finished, and a git that never answers would hold the thread for as long as it liked. Killing
   * it from the side closes the stream and the read returns.
   */
  private static List<String> run(File directory, String... command) throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command).directory(directory).redirectErrorStream(true);
    Process process = builder.start();

    Thread watchdog = new Thread(() -> {
      try {
        if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, "git-watchdog-" + directory.getName());
    watchdog.setDaemon(true);
    watchdog.start();

    List<String> lines;
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      lines = reader.lines().toList();
    } finally {
      process.waitFor();
      watchdog.interrupt();
    }

    if (process.exitValue() != 0) {
      throw new IOException("git exited " + process.exitValue()
          + (lines.isEmpty() ? " (it may have been killed for exceeding " + GIT_TIMEOUT.toSeconds() + "s)"
             : ": " + lines.get(0)));
    }
    return lines;
  }

  // ---- disk --------------------------------------------------------------------------------------

  /**
   * One filesystem's room.
   *
   * @param path        the directory the figures were read through
   * @param totalBytes  the filesystem's size
   * @param usableBytes what is left for this process
   * @param usedPercent how full it is, rounded to one decimal
   */
  public record FilesystemUsage(String path, long totalBytes, long usableBytes, double usedPercent) {
  }

  /**
   * One log file.
   *
   * @param path         absolute path
   * @param sizeBytes    size on disk
   * @param modifiedAt   last write, ISO-8601
   * @param ageDays      days since that write, so a file that stopped being written to stands out
   */
  public record LogFile(String path, long sizeBytes, String modifiedAt, long ageDays) {
  }

  /** The room left on each named directory's filesystem. Unreadable paths are skipped, not reported as zero. */
  public static List<FilesystemUsage> filesystems(List<String> paths) {
    List<FilesystemUsage> usages = new ArrayList<>();
    for (String path : paths) {
      if (path == null || path.isBlank()) {
        continue;
      }
      File file = new File(path);
      if (!file.exists()) {
        continue;
      }
      long total = file.getTotalSpace();
      if (total <= 0) {
        continue;
      }
      long usable = file.getUsableSpace();
      double usedPercent = Math.round((total - usable) * 1000.0 / total) / 10.0;
      usages.add(new FilesystemUsage(file.getAbsolutePath(), total, usable, usedPercent));
    }
    return usages;
  }

  /**
   * The largest files under a log directory, with their totals.
   *
   * <p>Size alone does not say whether rotation is working, but size beside age does: a single
   * multi-gigabyte file last written to seconds ago is a log nothing is rotating, and this is the
   * shape production's nginx access log had while going unrotated for a year.
   */
  public static Map<String, Object> logFiles(String directory) {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("directory", directory);

    File root = directory == null ? null : new File(directory);
    if (root == null || !root.isDirectory()) {
      report.put("readable", false);
      report.put("files", List.of());
      report.put("totalBytes", 0L);
      report.put("fileCount", 0);
      return report;
    }

    List<LogFile> files = new ArrayList<>();
    long totalBytes = 0;
    Instant now = Instant.now();
    try (Stream<Path> walk = Files.walk(root.toPath())) {
      List<Path> regular = walk.filter(Files::isRegularFile).toList();
      for (Path path : regular) {
        try {
          long size = Files.size(path);
          Instant modified = Files.getLastModifiedTime(path).toInstant();
          totalBytes += size;
          files.add(new LogFile(
              path.toAbsolutePath().toString(),
              size,
              modified.toString(),
              Duration.between(modified, now).toDays()));
        } catch (IOException e) {
          // A file that vanished mid-walk, or one this process may not stat. Neither is the report's problem.
          log.debug("Skipping a log file that could not be read: {}", path, e);
        }
      }
    } catch (IOException e) {
      report.put("readable", false);
      report.put("error", messageOf(e));
      report.put("files", List.of());
      report.put("totalBytes", 0L);
      report.put("fileCount", 0);
      return report;
    }

    int fileCount = files.size();
    files.sort(Comparator.comparingLong(LogFile::sizeBytes).reversed());

    report.put("readable", true);
    report.put("fileCount", fileCount);
    report.put("totalBytes", totalBytes);
    report.put("files", files.size() > LARGEST_LOG_FILES ? files.subList(0, LARGEST_LOG_FILES) : files);
    return report;
  }

  private static String messageOf(Throwable e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }
}
