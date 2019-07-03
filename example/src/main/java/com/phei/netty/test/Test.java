package com.phei.netty.test;


import java.util.LinkedList;
import java.util.List;

class TreeNode {
    int val;
    TreeNode left;
    TreeNode right;

    TreeNode(int x) {
        val = x;
    }
}

class Solution {
    private static int M = (int) (Math.pow(10,9) + 7);
    public int sumRootToLeaf(TreeNode root) {
        List<String> strs = new LinkedList<>();
        track("", root, strs);
        long ans = 0;
        for (String str : strs) {
            try {
                ans += Long.valueOf(str, 2);
            } catch (Throwable t) {
                ans += Long.valueOf(str.substring(0, 64), 2);
            }
        }
        return (int) (ans % M);
    }

    public void track(String pre, TreeNode node, List<String> strs) {
        if (node.left == null && node.right == null) {
            strs.add(pre + node.val);
            return;
        }
        if (node.left != null)
            track(pre + node.val, node.left, strs);
        if (node.right != null)
            track(pre + node.val, node.right, strs);
    }

    public static void main(String[] args) {
        System.out.println((int)(Math.pow(10,9)+ 7));
    }
}
