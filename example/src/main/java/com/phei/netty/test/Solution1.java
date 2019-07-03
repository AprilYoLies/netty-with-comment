package com.phei.netty.test;

import java.math.BigInteger;
import java.util.Scanner;

public class Solution1 {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int s = sc.nextInt();
        BigInteger[] n;
        if (s < 5) n = new BigInteger[5];
        else n = new BigInteger[s + 1];
        n[1] = BigInteger.valueOf(1);
        n[2] = BigInteger.valueOf(2);
        n[3] = BigInteger.valueOf(4);
        n[4] = BigInteger.valueOf(8);
        for (int i = 5; i <= s; i++)
            n[i] = n[i - 1].add(n[i - 2]).add(n[i - 3]).add(n[i - 4]);

        for (int i = 1; i < n.length; i++) {
            System.out.println(i + " ---> " + n[i].toString());
        }
    }
}