package com.chaconez.knowengine.ai;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不同LLM模型的ChatModel调用结果和耗时基准测试
 * <p>
 * 本测试直接构造ChatModel实例（不依赖Spring上下文），
 * 分别调用不同的LLM模型，比较返回结果和响应时间。
 * <p>
 * 注意：本测试会实际调用LLM API产生费用，默认@Disabled，需要手动运行。
 *
 * @author Chaconez
 */
@Disabled("集成测试，需要手动运行，会调用LLM API产生费用")
public class ChatModelBenchmarkTest {

    // ========== 配置区域（按需修改） ==========

    /**
     * DashScope（通义千问）API地址
     */
    private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /**
     * API Key：通过环境变量 DASHSCOPE_API_KEY 注入，避免硬编码泄漏
     */
    private static final String API_KEY = System.getenv("DASHSCOPE_API_KEY") == null
            ? "sk-xxx" : System.getenv("DASHSCOPE_API_KEY");

    /**
     * 要测试的模型列表
     */
    private static final List<String> MODEL_NAMES = List.of(
            "qwen-max-latest",
            "qwen3.6-max-preview",
            "qwen3.6-flash",
            "qwen3-30b-a3b-instruct-2507",
            "qwen3.5-27b",
            "deepseek-r1-distill-qwen-7b",
            "deepseek-r1-distill-qwen-32b"
    );

    /**
     * 测试用的提示词
     */
    private static final String TEST_PROMPT = "你是一个汽车智能客服助手，你的职责范围是汽车相关的咨询场景，包括购车咨询、车型信息、保养维修、保险年检、售后服务等。你需要对用户的问题进行改写，使得改写后的问题在查询向量数据库/关系型数据库/图数据库时有更好的结果，并删除任何无关信息，确保查询简洁明了、具体明确。下面有一些改写的策略。\\n\\n1、简洁改写。问题可能比较长，包含了一些无意义的语气词、修饰词或者重复的词语等。尤其是问题在询问车型配置、价格政策时，且包含一些无意义的日期、编号等修饰词。改写规则：删除无意义的词语使其更适合搜索引擎检索，疑问句要转成陈述句。\\n2、抽象概念改写。前提：用户的问题一定在询问汽车相关的问题，且是一些比较具体的细节问题，比如\\\"我的车每次踩刹车的时候都有吱吱吱的声音很吵怎么办\\\"。需要改写成类似\\\"车辆刹车异响故障排查\\\"，将具体的问题转化为更基础、更简洁、更抽象的问题。\\n3、错别字改写。用户的问题包含了错别字或者是一些常见的汽车术语用户打成了对应的拼音。大小写不一样不属于错别字。错别字需要给出纠正结果。\\n4、车型信息提取。如果用户提到了具体的车型信息（品牌、型号、年款等），需要将其标准化提取。比如\\\"特斯拉毛豆3\\\"改写为\\\"Tesla Model 3\\\"，\\\"比亚迪汉\\\"保持不变。\\n5、结合历史对话和最新提问，识别出所有相关的细节、术语和上下文信息。最后，将这条提问重新组织成一个清晰、简洁且独立完整的格式，以便于进行信息检索。\\n\\n上面是5种改写策略，需要逐一使用最终给出一个统一的改写结果。直接输出改写后的结果，不需要输出思考过程及额外的多余内容。如果不需要改写，则直接输出原问题即可。\\n\\n下面是几个示例：\\n\\nInput：我如果想买一辆特斯拉Model 3的话，大概需要多少钱啊\\nOutput：Tesla Model 3官方指导价\\n\\nInput：我的车该保养了，多久保养一次？\\nOutput：车辆保养周期规定\\n\\nInput：我的比亚迪汉刹车有点异响是怎么回事\\nOutput：车辆刹车异响故障排查\\n\\nInput：毛豆Y续航多少\\nOutput：Tesla Model Y续航里程\\n\\nInput：保险什么时候到期？\\nOutput：车辆保险到期查询\\n\\nInput：年检怎么办理？\\nOutput：车辆年检办理流程\\n\\nInput：我想了解一下你们那款新出的电动车的配置\\nOutput：新款电动车车型配置参数\\n\\n历史对话内容：\\nUser: 我的车汽车打不着火怎么回事\\nUser: 我的车辆是：宝马 3系 2025款 325Li M运动套装（京B·M11223）\\nYou must answer strictly in the following JSON format: {\\n\\\"reasoning\\\": (type: string),\\n\\\"related\\\": (type: boolean),\\n\\\"intent\\\": (type: string),\\n\\\"entities\\\": (type: com.chaconez.knowengine.ai.model.IntentRecognitionResult$Entities: {\\n\\\"car_model\\\": (type: string),\\n\\\"car_id\\\": (type: string),\\n\\\"order_id\\\": (type: string),\\n\\\"dealer\\\": (type: string),\\n\\\"fault_description\\\": (type: string),\\n\\\"appointment_time\\\": (type: string),\\n\\\"part_name\\\": (type: string),\\n\\\"function_name\\\": (type: string)\\n})\\n}\\nAI: {\\n\\\"reasoning\\\": \\\"1.相关性：涉及宝马3系，相关。2.场景：用车阶段。3.辨析：用户描述‘打不着火’，可能是寻求故障原因或解决方法，属于技术咨询而非进店维修。-> 车辆使用与技术指导\\\",\\n\\\"related\\\": true,\\n\\\"intent\\\": \\\"车辆使用与技术指导\\\",\\n\\\"entities\\\": {\\n    \\\"car_model\\\": \\\"宝马 3系 2025款 325Li M运动套装\\\",\\n    \\\"car_id\\\": \\\"京B·M11223\\\",\\n    \\\"order_id\\\": null,\\n    \\\"dealer\\\": null,\\n    \\\"fault_description\\\": \\\"打不着火\\\",\\n    \\\"appointment_time\\\": null,\\n    \\\"part_name\\\": null,\\n    \\\"function_name\\\": null\\n    }\\n}\\n\\n用户提问：我的车辆是：宝马 3系 2025款 325Li M运动套装（京B·M11223）\\n\\n非常重要的一点是：你只需要提供重新组织后的提问，不要包含任何其他内容！绝对不要在提问前添加任何多余的文字！\\n";

    // ========== 测试方法 ==========

    @Test
    @DisplayName("基准测试：不同模型的调用结果和耗时对比")
    void benchmarkDifferentModels() {
        System.out.println("=" .repeat(80));
        System.out.println("LLM模型基准测试");
        System.out.println("提示词: " + TEST_PROMPT);
        System.out.println("=" .repeat(80));

        Map<String, BenchmarkResult> results = new LinkedHashMap<>();

        for (String modelName : MODEL_NAMES) {
            System.out.println("\n>>> 正在测试模型: " + modelName);
            BenchmarkResult result = callModel(modelName, TEST_PROMPT);
            results.put(modelName, result);

            // 实时输出每个模型的结果
            System.out.println("    状态: " + (result.success ? "✓ 成功" : "✗ 失败"));
            System.out.println("    耗时: " + result.latencyMs + " ms");
            if (result.success) {
                System.out.println("    回复: " + result.response);
            } else {
                System.out.println("    错误: " + result.errorMessage);
            }
        }

        // 打印汇总报告
        printSummaryReport(results);
    }

    @Test
    @DisplayName("基准测试：同一模型多次调用的稳定性和耗时波动")
    void benchmarkSameModelMultipleCalls() {
        String targetModel = "qwen3-30b-a3b-instruct-2507";
        int callCount = 20;

        System.out.println("=" .repeat(80));
        System.out.println("模型稳定性测试：" + targetModel + " × " + callCount + "次调用");
        System.out.println("提示词: " + TEST_PROMPT);
        System.out.println("=" .repeat(80));

        List<BenchmarkResult> results = new ArrayList<>();

        for (int i = 1; i <= callCount; i++) {
            System.out.println("\n>>> 第 " + i + " 次调用...");
            BenchmarkResult result = callModel(targetModel, TEST_PROMPT);
            results.add(result);

            System.out.println("    耗时: " + result.latencyMs + " ms");
            if (result.success) {
                System.out.println("    回复: " + truncate(result.response, 80));
            } else {
                System.out.println("    错误: " + result.errorMessage);
            }
        }

        // 统计
        printStabilityReport(targetModel, results);
    }

    // ========== 辅助方法 ==========

    /**
     * 构建指定模型的ChatModel实例
     */
    private OpenAiChatModel buildChatModel(String modelName) {

        return OpenAiChatModel.builder()
                .apiKey(API_KEY)
                .modelName(modelName)
                .temperature(0.7)
                .baseUrl(BASE_URL)
                .customParameters(Map.of("enable_thinking", false))
                .build();
    }

    /**
     * 调用模型并记录耗时
     */
    private BenchmarkResult callModel(String modelName, String prompt) {
        BenchmarkResult result = new BenchmarkResult();
        result.modelName = modelName;

        try {
            OpenAiChatModel chatModel = buildChatModel(modelName);

            long startTime = System.currentTimeMillis();
            String response = chatModel.chat(prompt);
            long endTime = System.currentTimeMillis();

            result.latencyMs = endTime - startTime;
            result.response = response;
            result.success = true;
        } catch (Exception e) {
            result.latencyMs = 0;
            result.success = false;
            result.errorMessage = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        return result;
    }

    /**
     * 打印汇总报告
     */
    private void printSummaryReport(Map<String, BenchmarkResult> results) {
        System.out.println("\n" + "=" .repeat(80));
        System.out.println("汇总报告");
        System.out.println("-" .repeat(80));
        System.out.printf("%-20s %-8s %-12s %-40s%n", "模型", "状态", "耗时(ms)", "回复摘要");
        System.out.println("-" .repeat(80));

        results.forEach((model, result) -> {
            String status = result.success ? "✓ 成功" : "✗ 失败";
            String summary = result.success ? truncate(result.response, 40) : result.errorMessage;
            System.out.printf("%-20s %-8s %-12d %-40s%n", model, status, result.latencyMs, summary);
        });

        System.out.println("-" .repeat(80));

        // 找出最快和最慢
        results.entrySet().stream()
                .filter(e -> e.getValue().success)
                .min((a, b) -> Long.compare(a.getValue().latencyMs, b.getValue().latencyMs))
                .ifPresent(e -> System.out.println("最快模型: " + e.getKey() + " (" + e.getValue().latencyMs + " ms)"));

        results.entrySet().stream()
                .filter(e -> e.getValue().success)
                .max((a, b) -> Long.compare(a.getValue().latencyMs, b.getValue().latencyMs))
                .ifPresent(e -> System.out.println("最慢模型: " + e.getKey() + " (" + e.getValue().latencyMs + " ms)"));

        System.out.println("=" .repeat(80));
    }

    /**
     * 打印稳定性报告
     */
    private void printStabilityReport(String modelName, List<BenchmarkResult> results) {
        System.out.println("\n" + "=" .repeat(80));
        System.out.println("稳定性报告: " + modelName);
        System.out.println("-" .repeat(80));

        long[] latencies = results.stream()
                .filter(r -> r.success)
                .mapToLong(r -> r.latencyMs)
                .toArray();

        if (latencies.length > 0) {
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE, sum = 0;
            for (long l : latencies) {
                min = Math.min(min, l);
                max = Math.max(max, l);
                sum += l;
            }
            double avg = (double) sum / latencies.length;

            System.out.printf("成功次数: %d / %d%n", latencies.length, results.size());
            System.out.printf("平均耗时: %.0f ms%n", avg);
            System.out.printf("最小耗时: %d ms%n", min);
            System.out.printf("最大耗时: %d ms%n", max);
            System.out.printf("耗时波动: %d ms (max - min)%n", max - min);
        } else {
            System.out.println("所有调用均失败，无法统计耗时！");
        }
        System.out.println("=" .repeat(80));
    }

    /**
     * 截断字符串
     */
    private String truncate(String text, int maxLen) {
        if (text == null) return "null";
        text = text.replace("\n", " ").replace("\r", "");
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    // ========== 内部数据类 ==========

    /**
     * 基准测试结果
     */
    static class BenchmarkResult {
        String modelName;
        boolean success;
        long latencyMs;
        String response;
        String errorMessage;
    }
}
