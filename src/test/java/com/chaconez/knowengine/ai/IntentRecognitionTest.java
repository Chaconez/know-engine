package com.chaconez.knowengine.ai;

import com.chaconez.knowengine.ai.model.IntentRecognitionResult;
import com.chaconez.knowengine.ai.service.IntentRecognitionOldPromptService;
import com.chaconez.knowengine.ai.service.IntentRecognitionService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.Ignore;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 意图识别测试
 *
 * @author Chaconez
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Ignore("集成测试，需要手动运行，会调用LLM API产生费用")
public class IntentRecognitionTest {

    @Autowired
    private IntentRecognitionService intentService;

    @Autowired
    private IntentRecognitionOldPromptService oldPromptService;

    /**
     * 测试用例数据结构
     */
    static class TestCase {
        int id;
        String category;
        String input;
        ExpectedResult expected;

        static class ExpectedResult {
            boolean related;
            String intent;
            Map<String, String> entities;

            @Override
            public String toString() {
                return entities != null ? entities.toString() : "{}";
            }
        }
    }

    /**
     * 测试结果
     */
    static class TestResult {
        TestCase testCase;
        IntentRecognitionResult result;
        boolean correct;
        String error;
        long latencyMs;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%d] %s", testCase.id, testCase.input));
            sb.append(String.format("  期望: related=%s, intent=%s",
                    testCase.expected.related, testCase.expected.intent));
            if (result != null) {
                sb.append(String.format("  实际: related=%s, intent=%s | %s | 耗时:%dms",
                        result.related(),
                        result.intent(),
                        correct ? "✓正确" : "✗错误" + (error != null ? "(" + error + ")" : ""),
                        latencyMs));
            } else {
                sb.append(String.format("  实际: null | ✗错误 | 耗时:%dms", latencyMs));
            }
            return sb.toString();
        }
    }

    /**
     * 对比测试结果
     */
    static class ComparisonResult {
        TestCase testCase;
        IntentRecognitionResult newPromptResult;
        IntentRecognitionResult oldPromptResult;
        boolean newPromptCorrect;
        boolean oldPromptCorrect;
        long newPromptLatencyMs;
        long oldPromptLatencyMs;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【%d】%s", testCase.id, testCase.input));
            sb.append(String.format("分类: %s", testCase.category));
            sb.append(String.format("期望: related=%s, intent=%s",
                    testCase.expected.related, testCase.expected.intent));

            // 新版提示词结果
            sb.append("  [新版提示词] ");
            if (newPromptResult != null) {
                sb.append(String.format("related=%s, intent=%s | %s | 耗时:%dms",
                        newPromptResult.related(),
                        newPromptResult.intent(),
                        newPromptCorrect ? "✓正确" : "✗错误",
                        newPromptLatencyMs));
            } else {
                sb.append(String.format("null | ✗错误 | 耗时:%dms", newPromptLatencyMs));
            }

            // 旧版提示词结果
            sb.append("  [旧版提示词] ");
            if (oldPromptResult != null) {
                sb.append(String.format("related=%s, intent=%s | %s | 耗时:%dms",
                        oldPromptResult.related(),
                        oldPromptResult.intent(),
                        oldPromptCorrect ? "✓正确" : "✗错误",
                        oldPromptLatencyMs));
            } else {
                sb.append(String.format("null | ✗错误 | 耗时:%dms", oldPromptLatencyMs));
            }

            // 对比结论
            if (newPromptCorrect && !oldPromptCorrect) {
                sb.append("  → 新版胜出 ✓");
            } else if (!newPromptCorrect && oldPromptCorrect) {
                sb.append("  → 旧版胜出 ✓");
            } else if (newPromptCorrect && oldPromptCorrect) {
                sb.append("  → 两者都正确 =");
            } else {
                sb.append("  → 两者都错误 ✗");
            }

            return sb.toString();
        }
    }

    /**
     * 加载测试数据集
     */
    private List<TestCase> loadTestCases() throws IOException {
        return loadTestCases("intent-test-dataset.json");
    }

    /**
     * 加载指定测试数据集
     */
    private List<TestCase> loadTestCases(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JSONArray array = JSON.parseArray(content);

        List<TestCase> testCases = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject obj = array.getJSONObject(i);
            TestCase tc = new TestCase();
            tc.id = obj.getIntValue("id");
            tc.category = obj.getString("category");
            tc.input = obj.getString("input");

            JSONObject expected = obj.getJSONObject("expected");
            tc.expected = new TestCase.ExpectedResult();

            // 读取 related 字段
            if (expected.containsKey("related")) {
                tc.expected.related = expected.getBooleanValue("related");
            }

            // 读取意图字段
            if (expected.containsKey("intent")) {
                tc.expected.intent = expected.getString("intent");
            }

            // 读取实体字段
            if (expected.containsKey("entities")) {
                JSONObject entitiesObj = expected.getJSONObject("entities");
                tc.expected.entities = new HashMap<>();
                for (String key : entitiesObj.keySet()) {
                    tc.expected.entities.put(key, entitiesObj.getString(key));
                }
            }

            testCases.add(tc);
        }
        return testCases;
    }

    /**
     * 执行测试
     */
    @Test
    @Disabled("需要手动运行，会调用LLM API产生费用")
    public void testIntentRecognition() throws IOException {
        List<TestCase> testCases = loadTestCases();
        List<TestResult> results = new ArrayList<>();

        int correctCount = 0;
        long totalLatency = 0;

        System.out.println("========== 意图识别测试 ==========");
        System.out.println("测试用例数量: " + testCases.size());
        System.out.println("=".repeat(60) + "");

        for (TestCase tc : testCases) {
            TestResult result = new TestResult();
            result.testCase = tc;

            long start = System.currentTimeMillis();
            try {
                result.result = intentService.chat(UUID.randomUUID().toString(),tc.input);
                result.correct = checkResult(result.result, tc.expected);
                if (result.correct) correctCount++;
            } catch (Exception e) {
                result.error = e.getMessage();
                result.correct = false;
            }
            result.latencyMs = System.currentTimeMillis() - start;
            totalLatency += result.latencyMs;

            results.add(result);
            System.out.println(result);
        }

        // 输出统计结果
        System.out.println("" + "=".repeat(60));
        System.out.println("========== 统计结果 ==========");
        System.out.println("=".repeat(60));
        System.out.printf("测试用例总数: %d", testCases.size());
        System.out.printf("正确率: %d/%d (%.1f%%)",
                correctCount, testCases.size(),
                100.0 * correctCount / testCases.size());
        System.out.printf("平均耗时: %.1fms", 1.0 * totalLatency / testCases.size());

        // 输出错误案例
        System.out.println("【错误案例】");
        results.stream().filter(r -> !r.correct).forEach(r ->
                System.out.println("  [" + r.testCase.id + "] " + r.testCase.input +
                        " -> 期望:" + r.testCase.expected.intent +
                        " 实际:" + (r.result != null ? r.result.intent() : "null")));
    }

    /**
     * 检查结果是否正确
     */
    private boolean checkResult(IntentRecognitionResult result, TestCase.ExpectedResult expected) {
        if (result == null) return false;

        // 检查 related 字段
        if (result.related() != expected.related) {
            return false;
        }

        // 如果 related=false，intent 应为"闲聊与通用问答"
        if (!expected.related) {
            return true;
        }

        // related=true 时，检查意图
        if (result.intent() == null) return false;

        // 检查意图是否匹配（支持模糊匹配）
        String expectedIntent = expected.intent;
        String actualIntent = result.intent();
        
        return matchIntent(expectedIntent, actualIntent);
    }

    /**
     * 意图匹配（支持模糊匹配）
     */
    private boolean matchIntent(String expected, String actual) {
        if (expected == null || actual == null) return false;
        // 完全匹配
        if (expected.equals(actual)) return true;
        // 包含匹配
        if (actual.contains(expected) || expected.contains(actual)) return true;
        // 关键词匹配
        return matchByKeywords(expected, actual);
    }

    /**
     * 通过关键词匹配意图
     */
    private boolean matchByKeywords(String expected, String actual) {
        // 售前相关
        if ((expected.contains("售前") || expected.contains("购买")) && 
            (actual.contains("售前") || actual.contains("购买") || actual.contains("营销"))) {
            return true;
        }
        // 售后相关
        if ((expected.contains("售后") || expected.contains("维修")) && 
            (actual.contains("售后") || actual.contains("维修") || actual.contains("保养"))) {
            return true;
        }
        // 技术支持相关
        if ((expected.contains("技术") || expected.contains("车辆使用")) && 
            (actual.contains("技术") || actual.contains("车辆使用") || actual.contains("技术指导"))) {
            return true;
        }
        // 投诉相关
        if (expected.contains("投诉") && actual.contains("投诉")) {
            return true;
        }
        // 客户关怀相关
        if ((expected.contains("客户关怀") || expected.contains("运营")) && 
            (actual.contains("客户关怀") || actual.contains("运营"))) {
            return true;
        }
        // 闲聊相关
        if (expected.contains("闲聊") && actual.contains("闲聊")) {
            return true;
        }
        // 其他
        if (expected.contains("其他") && actual.contains("其他")) {
            return true;
        }
        return false;
    }

    /**
     * 对比测试 - 同时测试新旧两版提示词效果
     */
    @Test
    @Disabled("需要手动运行，会调用LLM API产生费用")
    public void testPromptComparison() throws IOException {
        List<TestCase> testCases = loadTestCases();
        List<ComparisonResult> results = new ArrayList<>();

        int newPromptCorrect = 0;
        int oldPromptCorrect = 0;
        long newPromptTotalLatency = 0;
        long oldPromptTotalLatency = 0;

        System.out.println("" + "=".repeat(80));
        System.out.println("========== 意图识别提示词对比测试 ==========");
        System.out.println("=".repeat(80));
        System.out.println("测试用例数量: " + testCases.size());
        System.out.println("-".repeat(80));

        for (TestCase tc : testCases) {
            ComparisonResult result = new ComparisonResult();
            result.testCase = tc;

            // 测试新版提示词
            long newStart = System.currentTimeMillis();
            try {
                result.newPromptResult = intentService.chat(UUID.randomUUID().toString(),tc.input);
                result.newPromptCorrect = checkResult(result.newPromptResult, tc.expected);
                if (result.newPromptCorrect) newPromptCorrect++;
            } catch (Exception e) {
                result.newPromptCorrect = false;
            }
            result.newPromptLatencyMs = System.currentTimeMillis() - newStart;
            newPromptTotalLatency += result.newPromptLatencyMs;

            // 测试旧版提示词
            long oldStart = System.currentTimeMillis();
            try {
                result.oldPromptResult = oldPromptService.chat(tc.input);
                result.oldPromptCorrect = checkResult(result.oldPromptResult, tc.expected);
                if (result.oldPromptCorrect) oldPromptCorrect++;
            } catch (Exception e) {
                result.oldPromptCorrect = false;
            }
            result.oldPromptLatencyMs = System.currentTimeMillis() - oldStart;
            oldPromptTotalLatency += result.oldPromptLatencyMs;

            results.add(result);
            System.out.println(result);
        }

        // 输出统计结果
        System.out.println("" + "=".repeat(80));
        System.out.println("========== 对比测试统计结果 ==========");
        System.out.println("=".repeat(80));
        System.out.printf("测试用例总数: %d", testCases.size());
        System.out.println("-".repeat(80));
        System.out.printf("【新版提示词】正确率: %d/%d (%.1f%%) | 平均耗时: %.1fms",
                newPromptCorrect, testCases.size(),
                100.0 * newPromptCorrect / testCases.size(),
                1.0 * newPromptTotalLatency / testCases.size());
        System.out.printf("【旧版提示词】正确率: %d/%d (%.1f%%) | 平均耗时: %.1fms",
                oldPromptCorrect, testCases.size(),
                100.0 * oldPromptCorrect / testCases.size(),
                1.0 * oldPromptTotalLatency / testCases.size());
        System.out.println("-".repeat(80));

        // 分类统计
        printCategoryStats(results);

        // 输出差异案例
        System.out.println("" + "=".repeat(80));
        System.out.println("========== 差异案例分析 ==========");
        System.out.println("=".repeat(80));

        System.out.println("【新版正确但旧版错误】");
        results.stream()
                .filter(r -> r.newPromptCorrect && !r.oldPromptCorrect)
                .forEach(r -> System.out.printf("  [%d] %s -> 期望:%s",
                        r.testCase.id, r.testCase.input,
                        r.testCase.expected.intent));

        System.out.println("【旧版正确但新版错误】");
        results.stream()
                .filter(r -> !r.newPromptCorrect && r.oldPromptCorrect)
                .forEach(r -> System.out.printf("  [%d] %s -> 期望:%s",
                        r.testCase.id, r.testCase.input,
                        r.testCase.expected.intent));

        System.out.println("【两者都错误】");
        results.stream()
                .filter(r -> !r.newPromptCorrect && !r.oldPromptCorrect)
                .forEach(r -> System.out.printf("  [%d] %s -> 期望:%s | 新版:%s | 旧版:%s",
                        r.testCase.id, r.testCase.input,
                        r.testCase.expected.intent,
                        r.newPromptResult != null ? r.newPromptResult.intent() : "null",
                        r.oldPromptResult != null ? r.oldPromptResult.intent() : "null"));
    }

    /**
     * 按分类统计
     */
    private void printCategoryStats(List<ComparisonResult> results) {
        System.out.println("【分类统计】");
        Map<String, int[]> categoryStats = new HashMap<>(); // [newCorrect, oldCorrect, total]

        for (ComparisonResult r : results) {
            String category = r.testCase.category.split("-")[0]; // 取主分类
            categoryStats.computeIfAbsent(category, k -> new int[3]);
            int[] stats = categoryStats.get(category);
            if (r.newPromptCorrect) stats[0]++;
            if (r.oldPromptCorrect) stats[1]++;
            stats[2]++;
        }

        System.out.printf("%-20s | %-20s | %-20s | %s", "分类", "新版正确率", "旧版正确率", "用例数");
        System.out.println("-".repeat(80));
        categoryStats.forEach((category, stats) -> {
            System.out.printf("%-20s | %-20s | %-20s | %d",
                    category,
                    String.format("%.1f%%", 100.0 * stats[0] / stats[2]),
                    String.format("%.1f%%", 100.0 * stats[1] / stats[2]),
                    stats[2]);
        });
    }

    /**
     * 对比测试 - 专门测试易混淆场景
     */
    @Test
    @Disabled("需要手动运行，会调用LLM API产生费用")
    public void testConfusionScenarios() throws IOException {
        List<TestCase> allTestCases = loadTestCases();
        // 筛选易混淆场景测试用例
        List<TestCase> confusionCases = allTestCases.stream()
                .filter(tc -> tc.category.startsWith("易混淆"))
                .toList();

        System.out.println("" + "=".repeat(80));
        System.out.println("========== 易混淆场景专项测试 ==========");
        System.out.println("=".repeat(80));
        System.out.println("测试用例数量: " + confusionCases.size());
        System.out.println("-".repeat(80));

        int newPromptCorrect = 0;
        int oldPromptCorrect = 0;

        for (TestCase tc : confusionCases) {
            System.out.printf("【%d】%s", tc.id, tc.input);
            System.out.printf("分类: %s", tc.category);
            System.out.printf("期望: related=%s, intent=%s",
                    tc.expected.related, tc.expected.intent);

            // 测试新版提示词
            long newStart = System.currentTimeMillis();
            IntentRecognitionResult newResult = null;
            boolean newCorrect = false;
            try {
                newResult = intentService.chat(UUID.randomUUID().toString(),tc.input);
                newCorrect = checkResult(newResult, tc.expected);
                if (newCorrect) newPromptCorrect++;
            } catch (Exception e) {
                // ignore
            }
            long newLatency = System.currentTimeMillis() - newStart;

            // 测试旧版提示词
            long oldStart = System.currentTimeMillis();
            IntentRecognitionResult oldResult = null;
            boolean oldCorrect = false;
            try {
                oldResult = oldPromptService.chat(tc.input);
                oldCorrect = checkResult(oldResult, tc.expected);
                if (oldCorrect) oldPromptCorrect++;
            } catch (Exception e) {
                // ignore
            }
            long oldLatency = System.currentTimeMillis() - oldStart;

            // 输出对比结果
            System.out.println("  [新版提示词] ");
            if (newResult != null) {
                System.out.printf("    related=%s, intent=%s | %s | 耗时:%dms",
                        newResult.related(),
                        newResult.intent(),
                        newCorrect ? "✓正确" : "✗错误",
                        newLatency);
            } else {
                System.out.printf("    null | ✗错误 | 耗时:%dms", newLatency);
            }

            System.out.println("  [旧版提示词] ");
            if (oldResult != null) {
                System.out.printf("    related=%s, intent=%s | %s | 耗时:%dms",
                        oldResult.related(),
                        oldResult.intent(),
                        oldCorrect ? "✓正确" : "✗错误",
                        oldLatency);
            } else {
                System.out.printf("    null | ✗错误 | 耗时:%dms", oldLatency);
            }

            // 对比结论
            if (newCorrect && !oldCorrect) {
                System.out.println("  → 新版胜出 ✓ (CoT推理成功辨析)");
            } else if (!newCorrect && oldCorrect) {
                System.out.println("  → 旧版胜出 ✓");
            } else if (newCorrect && oldCorrect) {
                System.out.println("  → 两者都正确 =");
            } else {
                System.out.println("  → 两者都错误 ✗");
            }
        }

        // 输出统计结果
        System.out.println("" + "=".repeat(80));
        System.out.println("========== 易混淆场景统计结果 ==========");
        System.out.println("=".repeat(80));
        System.out.printf("测试用例总数: %d", confusionCases.size());
        System.out.println("-".repeat(80));
        System.out.printf("【新版提示词】正确率: %d/%d (%.1f%%)",
                newPromptCorrect, confusionCases.size(),
                100.0 * newPromptCorrect / confusionCases.size());
        System.out.printf("【旧版提示词】正确率: %d/%d (%.1f%%)",
                oldPromptCorrect, confusionCases.size(),
                100.0 * oldPromptCorrect / confusionCases.size());
        System.out.println("-".repeat(80));
        System.out.printf("新版提升: %.1f%%",
                100.0 * (newPromptCorrect - oldPromptCorrect) / confusionCases.size());
    }

    /**
     * 对比测试 - 专门测试易混淆场景
     */
    @Test
    @Disabled("需要手动运行，会调用LLM API产生费用")
    public void testBianjieScenarios() throws IOException {
        List<TestCase> allTestCases = loadTestCases();
        // 筛选易混淆场景测试用例
        List<TestCase> confusionCases = allTestCases.stream()
                .filter(tc -> tc.category.startsWith("边界"))
                .toList();

        System.out.println("" + "=".repeat(80));
        System.out.println("========== 边界场景专项测试 ==========");
        System.out.println("=".repeat(80));
        System.out.println("测试用例数量: " + confusionCases.size());
        System.out.println("-".repeat(80));

        int newPromptCorrect = 0;
        int oldPromptCorrect = 0;

        for (TestCase tc : confusionCases) {
            System.out.printf("【%d】%s", tc.id, tc.input);
            System.out.printf("分类: %s", tc.category);
            System.out.printf("期望: related=%s, intent=%s",
                    tc.expected.related, tc.expected.intent);

            // 测试新版提示词
            long newStart = System.currentTimeMillis();
            IntentRecognitionResult newResult = null;
            boolean newCorrect = false;
            try {
                newResult = intentService.chat(UUID.randomUUID().toString(),tc.input);
                newCorrect = checkResult(newResult, tc.expected);
                if (newCorrect) newPromptCorrect++;
            } catch (Exception e) {
                // ignore
            }
            long newLatency = System.currentTimeMillis() - newStart;

            // 测试旧版提示词
            long oldStart = System.currentTimeMillis();
            IntentRecognitionResult oldResult = null;
            boolean oldCorrect = false;
            try {
                oldResult = oldPromptService.chat(tc.input);
                oldCorrect = checkResult(oldResult, tc.expected);
                if (oldCorrect) oldPromptCorrect++;
            } catch (Exception e) {
                // ignore
            }
            long oldLatency = System.currentTimeMillis() - oldStart;

            // 输出对比结果
            System.out.println("  [新版提示词] ");
            if (newResult != null) {
                System.out.printf("    related=%s, intent=%s | %s | 耗时:%dms",
                        newResult.related(),
                        newResult.intent(),
                        newCorrect ? "✓正确" : "✗错误",
                        newLatency);
            } else {
                System.out.printf("    null | ✗错误 | 耗时:%dms", newLatency);
            }

            System.out.println("  [旧版提示词] ");
            if (oldResult != null) {
                System.out.printf("    related=%s, intent=%s | %s | 耗时:%dms",
                        oldResult.related(),
                        oldResult.intent(),
                        oldCorrect ? "✓正确" : "✗错误",
                        oldLatency);
            } else {
                System.out.printf("    null | ✗错误 | 耗时:%dms", oldLatency);
            }

            // 对比结论
            if (newCorrect && !oldCorrect) {
                System.out.println("  → 新版胜出 ✓ (CoT推理成功辨析)");
            } else if (!newCorrect && oldCorrect) {
                System.out.println("  → 旧版胜出 ✓");
            } else if (newCorrect && oldCorrect) {
                System.out.println("  → 两者都正确 =");
            } else {
                System.out.println("  → 两者都错误 ✗");
            }
        }

        // 输出统计结果
        System.out.println("" + "=".repeat(80));
        System.out.println("========== 易混淆场景统计结果 ==========");
        System.out.println("=".repeat(80));
        System.out.printf("测试用例总数: %d", confusionCases.size());
        System.out.println("-".repeat(80));
        System.out.printf("【新版提示词】正确率: %d/%d (%.1f%%)",
                newPromptCorrect, confusionCases.size(),
                100.0 * newPromptCorrect / confusionCases.size());
        System.out.printf("【旧版提示词】正确率: %d/%d (%.1f%%)",
                oldPromptCorrect, confusionCases.size(),
                100.0 * oldPromptCorrect / confusionCases.size());
        System.out.println("-".repeat(80));
        System.out.printf("新版提升: %.1f%%",
                100.0 * (newPromptCorrect - oldPromptCorrect) / confusionCases.size());
    }

    /**
     * 对比测试 - 非汽车相关判断场景
     */
    @Test
    @Disabled("需要手动运行，会调用LLM API产生费用")
    public void testNonAutoRelatedScenarios() throws IOException {
        List<TestCase> allTestCases = loadTestCases();
        // 筛选非汽车相关测试用例
        List<TestCase> nonAutoCases = allTestCases.stream()
                .filter(tc -> tc.category.startsWith("闲聊与通用问答") || 
                              tc.category.startsWith("非汽车相关"))
                .toList();

        System.out.println("" + "=".repeat(80));
        System.out.println("========== 非汽车相关判断专项测试 ==========");
        System.out.println("========== （体现New Prompt相关性判断优势） ==========");
        System.out.println("=".repeat(80));
        System.out.println("测试用例数量: " + nonAutoCases.size());
        System.out.println("-".repeat(80));

        int newPromptCorrect = 0;
        int oldPromptCorrect = 0;

        for (TestCase tc : nonAutoCases) {
            System.out.printf("【%d】%s", tc.id, tc.input);
            System.out.printf("期望: related=%s", tc.expected.related);

            // 测试新版
            IntentRecognitionResult newResult = null;
            boolean newCorrect = false;
            try {
                newResult = intentService.chat(UUID.randomUUID().toString(),tc.input);
                newCorrect = (newResult != null && newResult.related() == tc.expected.related);
                if (newCorrect) newPromptCorrect++;
            } catch (Exception e) {
                // ignore
            }

            // 测试旧版
            IntentRecognitionResult oldResult = null;
            boolean oldCorrect = false;
            try {
                oldResult = oldPromptService.chat(tc.input);
                oldCorrect = (oldResult != null && oldResult.related() == tc.expected.related);
                if (oldCorrect) oldPromptCorrect++;
            } catch (Exception e) {
                // ignore
            }

            System.out.printf("  新版: related=%s %s",
                    newResult != null ? newResult.related() : "null",
                    newCorrect ? "✓" : "✗");
            System.out.printf("  旧版: related=%s %s",
                    oldResult != null ? oldResult.related() : "null",
                    oldCorrect ? "✓" : "✗");
        }

        System.out.println("" + "=".repeat(80));
        System.out.printf("【新版提示词】正确率: %d/%d (%.1f%%)",
                newPromptCorrect, nonAutoCases.size(),
                100.0 * newPromptCorrect / nonAutoCases.size());
        System.out.printf("【旧版提示词】正确率: %d/%d (%.1f%%)",
                oldPromptCorrect, nonAutoCases.size(),
                100.0 * oldPromptCorrect / nonAutoCases.size());
    }

    /**
     * 对比测试 - 实体提取场景
     */
    @Test
    @Disabled("需要手动运行，会调用LLM API产生费用")
    public void testEntityExtractionScenarios() throws IOException {
        List<TestCase> allTestCases = loadTestCases();
        // 筛选实体提取测试用例
        List<TestCase> entityCases = allTestCases.stream()
                .filter(tc -> tc.category.startsWith("实体提取"))
                .toList();

        System.out.println("" + "=".repeat(80));
        System.out.println("========== 实体提取专项测试 ==========");
        System.out.println("=".repeat(80));
        System.out.println("测试用例数量: " + entityCases.size());
        System.out.println("-".repeat(80));

        for (TestCase tc : entityCases) {
            System.out.printf("【%d】%s", tc.id, tc.input);

            // 测试新版
            IntentRecognitionResult newResult = null;
            try {
                newResult = intentService.chat(UUID.randomUUID().toString(),tc.input);
            } catch (Exception e) {
                // ignore
            }

            // 测试旧版
            IntentRecognitionResult oldResult = null;
            try {
                oldResult = oldPromptService.chat(tc.input);
            } catch (Exception e) {
                // ignore
            }

            System.out.println("  期望实体: " + (tc.expected.entities != null ? tc.expected.entities.toString() : "无"));
            System.out.println("  新版实体: " + (newResult != null && newResult.entities() != null ?
                    formatEntities(newResult.entities()) : "无"));
            System.out.println("  旧版实体: " + (oldResult != null && oldResult.entities() != null ?
                    formatEntities(oldResult.entities()) : "无"));
        }
    }

    /**
     * 格式化实体信息
     */
    private String formatEntities(IntentRecognitionResult.Entities entities) {
        StringBuilder sb = new StringBuilder();
        if (entities.car_model() != null) sb.append("车型=").append(entities.car_model()).append(" ");
        if (entities.order_id() != null) sb.append("订单=").append(entities.order_id()).append(" ");
        if (entities.dealer() != null) sb.append("门店=").append(entities.dealer()).append(" ");
        if (entities.fault_description() != null) sb.append("故障=").append(entities.fault_description()).append(" ");
        if (entities.appointment_time() != null) sb.append("预约时间=").append(entities.appointment_time()).append(" ");
        if (entities.part_name() != null) sb.append("配件=").append(entities.part_name()).append(" ");
        if (entities.function_name() != null) sb.append("功能=").append(entities.function_name()).append(" ");
        return sb.toString().trim();
    }

    /**
     * 单个测试用例 - 用于快速验证
     */
    @Test
    @Disabled("需要手动运行，会调用LLM API产生费用")
    public void testSingleCase() {
        String input = "我的车仪表盘上亮了个像水龙头一样的黄灯，这是什么意思？还能继续开吗？";

        System.out.println("========== 单个测试用例 ==========");
        System.out.println("输入: " + input);

        long start = System.currentTimeMillis();
        try {
            IntentRecognitionResult result = intentService.chat(UUID.randomUUID().toString(),input);
            System.out.println("结果:");
            if (result != null) {
                System.out.println("  是否汽车相关: " + result.related());
                System.out.println("  意图: " + result.intent());
                if (result.entities() != null) {
                    System.out.println("  实体:");
                    System.out.println("    车型: " + result.entities().car_model());
                    System.out.println("    订单号: " + result.entities().order_id());
                    System.out.println("    故障描述: " + result.entities().fault_description());
                    System.out.println("    功能名称: " + result.entities().function_name());
                }
            } else {
                System.out.println("  null");
            }
        } catch (Exception e) {
            System.out.println("异常: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("耗时: " + (System.currentTimeMillis() - start) + "ms");
    }
}
