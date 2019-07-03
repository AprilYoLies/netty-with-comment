package com.phei.netty.test;

public abstract class AbstractDemo {
	abstract void eat();
	
	void getUp() {
		System.out.println("起床了~~~");
		eat();
	}
	
	public static void main(String[] args) {
		new AbstractSub().getUp();
	}
}
