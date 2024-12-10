import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main implements Runnable {
    public static void main(String[] args) throws InterruptedException, IOException {
        var t = new Thread(new Main());
        try (jdk.jfr.Recording recording = new jdk.jfr.Recording()) {
            recording.setName("test");
            recording.setDumpOnExit(true);
            recording.setDestination(Path.of("./out.jfr"));
            recording.enable(CustomEvent.class);
            recording.enable(CollectionSizeEvent.class);
            recording.setToDisk(true);
            recording.start();
            t.setDaemon(false);
            t.start();
            t.join();
        }
    }

    private final ScheduledExecutorService dispatcher = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    private final BlockingQueue<CustomEvent> q = new LinkedBlockingQueue<>(128);

    @Override
    public void run() {
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            executor.submit(() -> {
                while (!executor.isShutdown()) {
                    try {
                        var event = q.take();
                        event.run();
                        event.executor = Thread.currentThread();
                        event.end();
                        if (event.shouldCommit()) {
                            event.commit();
                        }
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            });
        }

        dispatcher.scheduleAtFixedRate(() -> {
            var event = new CustomEvent();
            event.dispatcher = Thread.currentThread();
            event.begin();
            if (event.isBlocking()) {
                q.offer(event);
            } else {
                event.executor = Thread.currentThread();
                event.run();
                event.end();
                if (event.shouldCommit()) {
                    event.commit();
                }
            }
        }, 0, 5, TimeUnit.MILLISECONDS);

        dispatcher.scheduleAtFixedRate(() -> {
            var evt = new CollectionSizeEvent(q);
            if (evt.shouldCommit()) {
                evt.commit();
            }
        }, 0, 1, TimeUnit.SECONDS);

        while (true) {
            try {
                System.out.print(". ");
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                executor.shutdown();
                dispatcher.shutdown();
                break;
            }
        }
    }

    static long fib(long n) {
        if (n == 0) {
            return 0;
        }
        if (n == 1) {
            return 1;
        }
        return fib(n - 1) + fib(n - 2);
    }

    static class CustomEvent extends jdk.jfr.Event implements Runnable {
        final String id = UUID.randomUUID().toString();
        Thread dispatcher;
        Thread executor;
        final long idx = (long) (Math.random() * 46);
        final boolean blocking = idx > 5;
        long fib = 0;

        boolean isBlocking() {
            return blocking;
        }

        @Override
        public void run() {
            this.fib = fib(idx);
        }
    }

    static class CollectionSizeEvent extends jdk.jfr.Event {
        final int size;

        CollectionSizeEvent(Collection<?> c) {
            this.size = c.size();
        }
    }
}
