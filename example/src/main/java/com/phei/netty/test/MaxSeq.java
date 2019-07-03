package com.phei.netty.test;

public class MaxSeq {
    public static void main(String[] args) {
        int[] arr = {2, -1, 3, -2, 7, -9, 2, -5, 9, 8, 2, -6, 4, 8, -4, 7, -4};
        int[][] res = new int[arr.length][arr.length];
        int max = 0;
        int l = 0, r = 0;
        for (int i = 0; i < arr.length; i++) {
            for (int j = i; j < arr.length; j++) {
                res[i][j] = (i < 1) ? sum(arr, i, j) : res[0][j] - res[0][i - 1];
                if (res[i][j] > max) {
                    max = res[i][j];
                    l = i;
                    r = j;
                }
            }
        }
        int ans = sum(arr, l, r);
        System.out.println();
    }

    private static int sum(int[] nums, int i, int j) {
        int res = 0;
        for (int k = i; k <= j; k++) {
            res += nums[k];
        }
        return res;
    }
}
