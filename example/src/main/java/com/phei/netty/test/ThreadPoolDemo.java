package com.phei.netty.test;

import java.util.concurrent.*;

public class ThreadPoolDemo {
    public static void main(String[] args) {
        ExecutorService exec = new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(1));
        exec.execute(() -> {
            System.out.println("Task 1");
        });
        exec.execute(() -> System.out.println("Task 2"));
    }
}
