package com.phei.netty.test;

public class TryTest {
	public static void main(String[] args) {
		try {
			System.out.println("before");
			int i = 10 / 0;
			System.out.println("after");
		}catch (Exception e) {
			System.out.println(e.getMessage());
		} finally {
			System.out.println("final");
		}
	}
}
