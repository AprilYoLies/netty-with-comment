package top.aprilyolies.example.common;

import io.netty.util.concurrent.RejectedExecutionHandlers;

import java.util.concurrent.*;

import static java.util.concurrent.TimeUnit.*;

/**
 * @Author EvaJohnson
 * @Date 2019-08-29
 * @Email g863821569@gmail.com
 */
public class ThreadPoolTest {
    public static void main(String[] args) {
        ExecutorService es = new ThreadPoolExecutor(5,
                10,
                3000,
                MILLISECONDS,
                new ArrayBlockingQueue<>(20),
                Thread::new, new ThreadPoolExecutor.AbortPolicy());

        es.execute(() -> System.out.println("Running.."));
    }
}
