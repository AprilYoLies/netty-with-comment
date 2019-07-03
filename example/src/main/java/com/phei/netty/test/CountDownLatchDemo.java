package com.phei.netty.test;

import java.util.concurrent.CountDownLatch;

public class CountDownLatchDemo {
    private static Integer N = 10;

    public static void main(String[] args) throws InterruptedException {
        new Driver().main();
    }

    static class Driver { // ...
        void main() throws InterruptedException {
            CountDownLatch startSignal = new CountDownLatch(1);
            CountDownLatch doneSignal = new CountDownLatch(N);
            for (int i = 0; i < N; ++i) // create and start threads
                new Thread(new Worker(startSignal, doneSignal)).start();
            doSomethingElse();            // don't let run yet
            startSignal.countDown();      // let all threads proceed
            doneSignal.await();
            System.out.println("All thread have finished their work...");
            // wait for all to finish
        }

        private void doSomethingElse() {
            System.out.println("Do something else");
        }
    }

    static class Worker implements Runnable {
        private final CountDownLatch startSignal;
        private final CountDownLatch doneSignal;

        Worker(CountDownLatch startSignal, CountDownLatch doneSignal) {
            this.startSignal = startSignal;
            this.doneSignal = doneSignal;
        }

        public void run() {
            try {
                startSignal.await();
                doWork();
                doneSignal.countDown();
            } catch (InterruptedException ex) {
            } // return;
        }

        void doWork() {
            System.out.println("Do some work...");
        }
    }
}