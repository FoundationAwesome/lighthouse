package lighthouse.protocol;

import com.google.bitcoin.core.*;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

public class LHUtils {
    private static final Logger log = LoggerFactory.getLogger(LHUtils.class);

    public static List<Path> listDir(Path dir) throws IOException {
        List<Path> contents = new LinkedList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.forEach(contents::add);
        }
        return contents;
    }

    public static String nowAsString() {
        return Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.RFC_1123_DATE_TIME);
    }

    public static boolean compareOutputsStructurally(Transaction tx, Project project) {
        List<TransactionOutput> outs1 = mapList(tx.getOutputs(), TransactionOutput::duplicateDetached);
        List<TransactionOutput> outs2 = mapList(project.getOutputs(), TransactionOutput::duplicateDetached);
        return outs1.equals(outs2);
    }

    public static boolean pledgeAppearsInClaim(Project forProject, LHProtos.Pledge pledge, Transaction claim) {
        Transaction tx = forProject.fastSanityCheck(pledge);
        List<TransactionInput> pledgeInputs = mapList(tx.getInputs(), TransactionInput::duplicateDetached);
        List<TransactionInput> claimInputs = mapList(claim.getInputs(), TransactionInput::duplicateDetached);
        claimInputs.retainAll(pledgeInputs);
        return claimInputs.size() == pledgeInputs.size();
    }

    public static String titleToUrlString(String s) {
        return s.replaceAll("[^a-zA-Z0-9 ]", "").replaceAll(" ", "-").toLowerCase();
    }

    public static boolean isUnix() {
        return System.getProperty("os.name").toLowerCase().contains("linux") || System.getProperty("os.name").toLowerCase().contains("freebsd");
    }

    //region Generic Java 8 enhancements
    public interface UncheckedRun<T> {
        public T run() throws Throwable;
    }

    public interface UncheckedRunnable {
        public void run() throws Throwable;
    }

    public static <T> T unchecked(UncheckedRun<T> run) {
        try {
            return run.run();
        } catch (Throwable throwable) {
            Throwables.propagate(throwable);
            return null;  // unreachable
        }
    }

    public static void uncheck(UncheckedRunnable run) {
        try {
            run.run();
        } catch (Throwable throwable) {
            Throwables.propagate(throwable);
        }
    }

    public static void ignoreAndLog(UncheckedRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            log.error("Ignoring error", t);
        }
    }

    public static <T> T ignoredAndLogged(UncheckedRun<T> runnable) {
        try {
            return runnable.run();
        } catch (Throwable t) {
            log.error("Ignoring error", t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static <T, E extends Throwable> T checkedGet(Future<T> future) throws E {
        try {
            return future.get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw (E) e.getCause();
        }
    }

    public static boolean didThrow(UncheckedRun run) {
        try {
            run.run();
            return false;
        } catch (Throwable throwable) {
            return true;
        }
    }

    public static boolean didThrow(UncheckedRunnable run) {
        try {
            run.run();
            return false;
        } catch (Throwable throwable) {
            return true;
        }
    }

    public static <T> T stopwatched(String description, UncheckedRun<T> run) {
        long now = System.currentTimeMillis();
        T result = unchecked(run::run);
        log.info("{}: {}ms", description, System.currentTimeMillis() - now);
        return result;
    }

    public static void stopwatch(String description, UncheckedRunnable run) {
        long now = System.currentTimeMillis();
        uncheck(run::run);
        log.info("{}: {}ms", description, System.currentTimeMillis() - now);
    }

    //endregion

    /** Given an HTTP[S] URL, verifies that it matches the CC protocol layout and returns the hostname if so. */
    @Nullable
    public static String validateServerPath(String path, String projectID) {
        try {
            if (path.isEmpty())
                return null;
            URI uri = new URI(path);
            if (uri.getScheme().equals("https") && uri.getPath().equals(HTTP_PATH_PREFIX + HTTP_PROJECT_PATH + projectID))
                return uri.getHost();
            else
                return null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public static final String HTTP_PATH_PREFIX = "/_lighthouse/crowdfund";
    public static final String HTTP_PROJECT_PATH = "/project/";
    public static final int HTTP_LOCAL_TEST_PORT = 13765;

    public static String makeServerPath(String name, String projectID) {
        return "https://" + name + HTTP_PATH_PREFIX + HTTP_PROJECT_PATH + projectID;
    }

    /** Convert from Guava futures to Java 8 futures */
    public static <T> CompletableFuture<T> convertFuture(ListenableFuture<T> future) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        Futures.addCallback(future, new FutureCallback<T>() {
            @Override
            public void onSuccess(@Nullable T result) {
                cf.complete(result);
            }

            @Override
            public void onFailure(Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        return cf;
    }

    /** Just simpler syntax than the stupid streams library */
    public static <T, R> List<R> mapList(List<T> inputs, Function<T, R> transformer) {
        List<R> results = new ArrayList<>(inputs.size());
        for (T input : inputs) results.add(transformer.apply(input));
        return results;
    }

    public static <T> CompletableFuture<T> futureOfFutures(List<CompletableFuture<T>> futures) {
        // It's dumb that the CF API is so huge and doesn't have this.
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicInteger numPending = new AtomicInteger(futures.size());
        for (CompletableFuture<T> future : futures) {
            future.handle((v, ex) -> {
                if (ex != null) {
                    log.error("Future failed with an exception: {}", Throwables.getRootCause(ex).toString());
                }
                if (numPending.decrementAndGet() == 0)
                    result.complete(v);
                return null;
            });
        }
        return result;
    }

    /** Either hashes the given pledge protobuf or returns the hash it claims to have been originally. */
    public static Sha256Hash hashFromPledge(LHProtos.Pledge pledge) {
        if (pledge.hasOrigHash())
            return new Sha256Hash(pledge.getOrigHash().toByteArray());
        else
            return Sha256Hash.create(pledge.toByteArray());
    }
}
