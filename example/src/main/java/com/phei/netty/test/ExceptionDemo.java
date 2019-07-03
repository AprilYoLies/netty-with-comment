package com.phei.netty.test;

public class ExceptionDemo {
	public static void main(String[] args) {
		try {
			System.out.println("before");
			int t = 1 / 0;
			System.out.println("after");
		} catch (Exception e) {
		}
	}
}
