import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 两个可执行的业务结果对照；不连接项目数据库或消息中间件。 */
public class MqUsefulnessDemo {
    private static final int ORDERS = 12;
    private static final int WORKERS = 2;
    private static final int WRITE_MS = 800;
    private static final int CONNECTION_WAIT_MS = 300;
    private static final int WORKING = 1, DONE = 2, FAILED = 3;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 3 && args[0].equals("--worker")) {
            System.exit(consumeFiles(Path.of(args[1]), args[2]));
        }
        check(args.length == 0 || (args.length == 2 && args[0].equals("--report")),
                "运行方式：java -Dfile.encoding=UTF-8 tools/mq-demo/MqUsefulnessDemo.java [--report 输出目录]");
        Path source = Path.of(System.getProperty("jdk.launcher.sourcefile")).toAbsolutePath();
        Path output = args.length == 2 ? Path.of(args[1]).toAbsolutePath() : source.getParent().resolve("results");
        Files.createDirectories(output);
        System.out.println("CityCopilot：比较最终业务结果，不比较受理时间与完成时间。\n");
        Scene burst = burst();
        Scene recovery = recovery(source, output);
        String data = "{\"scenes\":[" + burst.json() + "," + recovery.json() + "]}";
        Files.writeString(output.resolve("trace.json"), data, StandardCharsets.UTF_8);
        String template = Files.readString(source.getParent().resolve("demo-view.html"), StandardCharsets.UTF_8);
        check(template.contains("__TRACE_DATA__"), "回放模板缺少数据插槽：请检查 demo-view.html");
        Files.writeString(output.resolve("mq-outcomes.html"), template.replace("__TRACE_DATA__", data), StandardCharsets.UTF_8);
        System.out.println("\nPASS：并发上限、超时无落单、队列排空、磁盘保留、进程中断后恢复、重复不多算。");
        System.out.println("运行记录与回放：" + output);
        System.out.println("边界：线程/文件/JVM 进程实验；数据库耗时与服务故障为注入条件，未测试真实 Redis 或 RocketMQ。");
    }

    private static Scene burst() throws Exception {
        String conditions = "%d 个请求 / 每组最多并行 %d 单 / 每单 %d ms / 同步等连接限时 %d ms / 队列容量 %d"
                .formatted(ORDERS, WORKERS, WRITE_MS, CONNECTION_WAIT_MS, ORDERS);
        Scene scene = new Scene("餐券秒杀高峰", conditions, "同步等待数据库连接", "先排队，再按容量落单", ORDERS);
        System.out.println("场景一：" + conditions);
        System.out.println("假设请求已通过准入；同步等待连接超时后放弃本次下单，异步受理后的任务继续等待。\n");
        var queue = new ArrayBlockingQueue<Integer>(ORDERS);
        for (int id = 0; id < ORDERS; id++) queue.add(id);
        check(!queue.offer(ORDERS), "队满时应拒绝额外请求，不能宣称无限接单");
        var connections = new Semaphore(WORKERS);
        var ready = new CountDownLatch(ORDERS + WORKERS);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(ORDERS + WORKERS);
        var jobs = new ArrayList<Future<?>>();
        try {
            for (int id = 0; id < ORDERS; id++) {
                int order = id;
                jobs.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    long remaining = scene.started + TimeUnit.MILLISECONDS.toNanos(CONNECTION_WAIT_MS) - System.nanoTime();
                    if (remaining <= 0 || !connections.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
                        scene.update(false, order, FAILED, "同步请求 " + (order + 1) + " 等连接超时，本次未生成订单");
                        return null;
                    }
                    try {
                        scene.update(false, order, WORKING, "同步请求 " + (order + 1) + " 开始落单");
                        Thread.sleep(WRITE_MS);
                        scene.update(false, order, DONE, "同步订单 " + (order + 1) + " 已完成");
                    } finally {
                        connections.release();
                    }
                    return null;
                }));
            }
            for (int i = 0; i < WORKERS; i++) {
                jobs.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    Integer id;
                    while ((id = queue.poll()) != null) {
                        scene.update(true, id, WORKING, "消费者开始处理订单 " + (id + 1));
                        Thread.sleep(WRITE_MS);
                        scene.update(true, id, DONE, "排队订单 " + (id + 1) + " 已完成");
                    }
                    return null;
                }));
            }
            check(ready.await(10, TimeUnit.SECONDS), "实验线程未就绪，请检查本机线程资源后重跑");
            scene.start("同一批请求到达；两组后台使用相同处理能力");
            go.countDown();
            for (Future<?> job : jobs) job.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        check(scene.count(false, DONE) > 0 && scene.count(false, FAILED) > 0, "未观察到预期超时；请查看本机调度及超时条件");
        check(scene.count(false, DONE) + scene.count(false, FAILED) == ORDERS, "同步请求存在未终结状态");
        check(scene.count(true, DONE) == ORDERS && queue.isEmpty(), "队列未完整生成订单或仍有积压");
        check(scene.directPeak <= WORKERS && scene.queuedPeak <= WORKERS, "并行处理超过设定上限");
        scene.snapshot("队列方案完成更多订单，用了更长时间；两组单笔处理耗时相同");
        System.out.printf("\n最终：同步完成 %d，超时 %d；排队完成 %d，剩余 %d。%n",
                scene.count(false, DONE), scene.count(false, FAILED), scene.count(true, DONE), queue.size());
        System.out.println("这是有限请求等待时间下的对照；延长等待或持久化重试也可改变同步方案的结果。\n");
        return scene;
    }

    private static Scene recovery(Path source, Path output) throws Exception {
        Scene scene = new Scene("核销统计故障与恢复", "两组均已核销 3 笔 / 统计不可用 / 中断消费者 JVM / 启动新 JVM 重投",
                "直接调用统计，失败后未保存重试任务", "文件保存待办，消费确认后才删除", 3);
        Path run = Files.createTempDirectory(output, "recovery-");
        Files.createDirectories(run.resolve("pending"));
        Files.createDirectories(run.resolve("facts"));
        // 核销与发布之间的事务不在本实验范围；只验证已经保存的事件如何被消费。
        Files.writeString(run.resolve("used-bookings.txt"), "1\n2\n3\n", StandardCharsets.UTF_8);
        for (int id = 1; id <= 3; id++) {
            Files.writeString(run.resolve("pending/" + id + ".msg"), String.valueOf(id),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
        }
        scene.pending = 3;
        System.out.println("场景二：两组都已核销 3 笔，统计存储暂时不可用。");
        scene.start("两组核销均已成功；统计存储不可用");
        for (int id = 0; id < 3; id++) {
            try {
                requireStorage(true);
                throw new AssertionError("统计故障未触发");
            } catch (IOException expected) {
                scene.update(false, id, FAILED, "直接调用统计失败：未保存重试任务，核销本身已成功");
            }
        }
        check(runWorker(source, run, "offline", scene) == 75, "离线消费者没有返回预期失败状态");
        check(fileCount(run.resolve("pending")) == 3 && fileCount(run.resolve("facts")) == 0,
                "存储故障时应保留全部待办，不应记录消费成功");
        scene.snapshot("统计仍为 0；队列磁盘上保留 3 条待办");

        scene.snapshot("恢复统计存储；启动消费者，注入写入成功后、ACK 前进程中断");
        check(runWorker(source, run, "crash", scene) == 23, "消费者未在 ACK 前按预期中断");
        check(fileCount(run.resolve("pending")) == 3 && fileCount(run.resolve("facts")) == 1,
                "中断后应有 1 条统计事实和 3 条尚未确认的消息");
        scene.snapshot("消费者 JVM 已退出：统计 1 条，磁盘待确认仍有 3 条");

        scene.snapshot("启动全新消费者 JVM，从磁盘重读待办与已处理事实");
        check(runWorker(source, run, "recover", scene) == 0, "恢复消费失败，请检查运行目录与消费者输出");
        check(fileCount(run.resolve("pending")) == 0 && fileCount(run.resolve("facts")) == 3 && scene.duplicates == 1,
                "恢复后应有 3 条唯一事实、0 条待办，且重复事件被识别 1 次");
        check(Files.readAllLines(run.resolve("used-bookings.txt")).size() == 3, "消费者不应改变原始核销记录");
        check(scene.count(false, DONE) == 0 && scene.count(true, DONE) == 3, "统计结果与对照条件不符");
        scene.snapshot("直接调用统计仍为 0；队列补齐至 3，重投没有多算");
        System.out.println("\n最终：两组核销均为 3；直接调用统计 0，队列统计 3；待办 0；重复跳过 1。");
        System.out.println("磁盘证据：" + run);
        return scene;
    }

    private static int runWorker(Path source, Path run, String mode, Scene scene) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) java = java.resolveSibling("java");
        Process process = new ProcessBuilder(java.toString(), "-Dfile.encoding=UTF-8", source.toString(),
                "--worker", run.toString(), mode).redirectErrorStream(true).start();
        System.out.printf("  启动消费者 PID=%d，模式=%s%n", process.pid(), mode);
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", -1);
                if (parts.length != 2 || !Set.of("FAIL", "APPLY", "DUPLICATE", "ACK").contains(parts[0])) {
                    throw new IOException("消费者输出异常，PID=" + process.pid() + "：" + line);
                }
                int id = Integer.parseInt(parts[1]);
                switch (parts[0]) {
                    case "FAIL" -> scene.snapshot("消费者写统计失败；不 ACK，待办留在磁盘");
                    case "APPLY" -> scene.update(true, id - 1, DONE, "事件 " + id + " 已写入统计事实，尚未 ACK");
                    case "DUPLICATE" -> {
                        scene.duplicates++;
                        scene.snapshot("事件 " + id + " 再次投递；磁盘已有事实，跳过重复计数");
                    }
                    case "ACK" -> {
                        scene.pending--;
                        scene.snapshot("事件 " + id + " 已确认，从磁盘待办中移除");
                    }
                }
            }
            check(process.waitFor(10, TimeUnit.SECONDS), "消费者退出超时，PID=" + process.pid());
            System.out.printf("  消费者 PID=%d 已退出，退出码=%d%n", process.pid(), process.exitValue());
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static int consumeFiles(Path run, String mode) throws Exception {
        check(Set.of("offline", "crash", "recover").contains(mode), "未知消费者实验模式");
        List<Path> messages;
        try (var files = Files.list(run.resolve("pending"))) {
            messages = files.sorted().toList();
        }
        for (Path message : messages) {
            String id = Files.readString(message);
            check(id.matches("[1-3]") && message.getFileName().toString().equals(id + ".msg"), "实验消息身份不匹配：" + message);
            try {
                requireStorage(mode.equals("offline"));
            } catch (IOException expected) {
                signal("FAIL", id);
                return 75;
            }
            Path fact = run.resolve("facts/" + id + ".fact");
            // ponytail: 单机文件模拟业务事实唯一键；不覆盖多写者事务或机器断电，真实链路使用数据库 Inbox 事务。
            try {
                Files.writeString(fact, id, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
                signal("APPLY", id);
            } catch (FileAlreadyExistsException duplicate) {
                check(Files.readString(fact).equals(id), "重复事件的持久化事实内容冲突：" + id);
                signal("DUPLICATE", id);
            }
            if (mode.equals("crash")) Runtime.getRuntime().halt(23);
            Files.delete(message);
            signal("ACK", id);
        }
        return 0;
    }

    private static void requireStorage(boolean offline) throws IOException {
        if (offline) throw new IOException("统计存储不可用");
    }

    private static void signal(String type, String id) {
        System.out.println(type + "|" + id);
        System.out.flush();
    }

    private static long fileCount(Path directory) throws IOException {
        try (var files = Files.list(directory)) { return files.count(); }
    }

    private static class Scene {
        final String title, conditions, directLabel, queuedLabel;
        final int[] direct, queued;
        final List<String> frames = new ArrayList<>();
        long started;
        int pending = -1, duplicates, directPeak, queuedPeak;

        Scene(String title, String conditions, String directLabel, String queuedLabel, int size) {
            this.title = title;
            this.conditions = conditions;
            this.directLabel = directLabel;
            this.queuedLabel = queuedLabel;
            direct = new int[size];
            queued = new int[size];
        }

        synchronized void start(String message) {
            started = System.nanoTime();
            snapshot(message);
        }

        synchronized void update(boolean useQueue, int id, int state, String message) {
            int[] values = useQueue ? queued : direct;
            check(values[id] != DONE && values[id] != FAILED, "已结束业务被重复执行：" + (id + 1));
            values[id] = state;
            snapshot(message);
        }

        synchronized int count(boolean useQueue, int state) {
            return (int) Arrays.stream(useQueue ? queued : direct).filter(value -> value == state).count();
        }

        synchronized void snapshot(String message) {
            double ms = (System.nanoTime() - started) / 1_000_000.0;
            directPeak = Math.max(directPeak, count(false, WORKING));
            queuedPeak = Math.max(queuedPeak, count(true, WORKING));
            frames.add("{\"ms\":%.2f,\"direct\":%s,\"queued\":%s,\"pending\":%d,\"duplicates\":%d,\"note\":%s}"
                    .formatted(ms, Arrays.toString(direct), Arrays.toString(queued), pending, duplicates, quote(message)));
            System.out.printf("  %5.2f s | %s%n", ms / 1000, message);
        }

        String json() {
            return "{\"title\":%s,\"conditions\":%s,\"directLabel\":%s,\"queuedLabel\":%s,\"frames\":[%s]}"
                    .formatted(quote(title), quote(conditions), quote(directLabel), quote(queuedLabel), String.join(",", frames));
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("<", "\\u003c") + "\"";
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
