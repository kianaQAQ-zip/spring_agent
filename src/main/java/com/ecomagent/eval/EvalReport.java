package com.ecomagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 评估报告（§9）：每用例分数 + 聚合分。
 */
public record EvalReport(List<CaseScore> cases, double aggregateScore, int passed, int total) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public double passRate() {
        return total == 0 ? 0 : (double) passed / total;
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }

    public String toHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='UTF-8'>")
          .append("<title>Eval Report</title></head><body>")
          .append("<h1>电商客服 Agent 评估报告</h1>")
          .append("<p>聚合分：<b>").append(String.format("%.2f", aggregateScore * 100)).append("%</b> · 通过 ")
          .append(passed).append("/").append(total).append("</p>")
          .append("<table border='1' cellpadding='6' cellspacing='0'><tr>")
          .append("<th>ID</th><th>类别</th><th>关键词</th><th>意图</th><th>接地</th><th>忠实</th><th>总分</th><th>结果</th></tr>");
        for (CaseScore s : cases) {
            sb.append("<tr><td>").append(s.id()).append("</td><td>").append(s.category()).append("</td>")
              .append("<td>").append(fmt(s.keyword())).append("</td>")
              .append("<td>").append(fmt(s.intent())).append("</td>")
              .append("<td>").append(fmt(s.grounding())).append("</td>")
              .append("<td>").append(fmt(s.faithfulness())).append("</td>")
              .append("<td>").append(fmt(s.total())).append("</td>")
              .append("<td>").append(s.pass() ? "✅" : "❌").append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private String fmt(double v) {
        return String.format("%.2f", v);
    }
}
