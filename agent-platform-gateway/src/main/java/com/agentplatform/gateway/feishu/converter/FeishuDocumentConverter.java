package com.agentplatform.gateway.feishu.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuDocumentConverter {

    private final ObjectMapper objectMapper;

    public String convertToMarkdown(JsonNode documentContent, DocumentMetadata metadata) {
        StringBuilder markdown = new StringBuilder();

        // 1. 转换文档内容
        if (documentContent != null && documentContent.has("document")) {
            JsonNode document = documentContent.get("document");
            if (document.has("body")) {
                JsonNode body = document.get("body");
                if (body.has("blocks")) {
                    convertBlocks(body.get("blocks"), markdown, 0);
                }
            }
        }

        return markdown.toString();
    }

    public String convertToMarkdown(String documentContentJson, DocumentMetadata metadata) {
        try {
            JsonNode documentContent = objectMapper.readTree(documentContentJson);
            return convertToMarkdown(documentContent, metadata);
        } catch (Exception e) {
            log.error("Failed to parse document content JSON", e);
            throw new RuntimeException("Failed to parse document content", e);
        }
    }


    private void convertBlocks(JsonNode blocks, StringBuilder markdown, int depth) {
        if (blocks == null || !blocks.isArray()) {
            return;
        }

        for (JsonNode block : blocks) {
            convertBlock(block, markdown, depth);
        }
    }

    private void convertBlock(JsonNode block, StringBuilder markdown, int depth) {
        if (block == null) {
            return;
        }

        String blockType = block.has("block_type") ? block.get("block_type").asText() : "";

        switch (blockType) {
            case "1": // Page block
                if (block.has("children")) {
                    convertBlocks(block.get("children"), markdown, depth);
                }
                break;
            case "2": // Text block
                convertTextBlock(block, markdown);
                break;
            case "3": // Heading1
                convertHeadingBlock(block, markdown, 1);
                break;
            case "4": // Heading2
                convertHeadingBlock(block, markdown, 2);
                break;
            case "5": // Heading3
                convertHeadingBlock(block, markdown, 3);
                break;
            case "6": // Heading4
                convertHeadingBlock(block, markdown, 4);
                break;
            case "7": // Heading5
                convertHeadingBlock(block, markdown, 5);
                break;
            case "8": // Heading6
                convertHeadingBlock(block, markdown, 6);
                break;
            case "9": // Heading7
                convertHeadingBlock(block, markdown, 7);
                break;
            case "10": // Heading8
                convertHeadingBlock(block, markdown, 8);
                break;
            case "11": // Heading9
                convertHeadingBlock(block, markdown, 9);
                break;
            case "12": // Bullet list
                convertBulletListBlock(block, markdown, depth);
                break;
            case "13": // Ordered list
                convertOrderedListBlock(block, markdown, depth);
                break;
            case "14": // Code block
                convertCodeBlock(block, markdown);
                break;
            case "15": // Quote
                convertQuoteBlock(block, markdown);
                break;
            case "17": // Todo
                convertTodoBlock(block, markdown);
                break;
            case "18": // Divider
                markdown.append("\n---\n\n");
                break;
            case "19": // Image
                convertImageBlock(block, markdown);
                break;
            case "20": // Table
                convertTableBlock(block, markdown);
                break;
            case "22": // Callout
                convertCalloutBlock(block, markdown);
                break;
            case "27": // Grid
                if (block.has("children")) {
                    convertBlocks(block.get("children"), markdown, depth);
                }
                break;
            case "28": // Grid column
                if (block.has("children")) {
                    convertBlocks(block.get("children"), markdown, depth);
                }
                break;
            default:
                // 尝试处理未知类型的文本内容
                if (block.has("text")) {
                    convertTextBlock(block, markdown);
                } else if (block.has("children")) {
                    convertBlocks(block.get("children"), markdown, depth);
                }
                break;
        }
    }

    private void convertTextBlock(JsonNode block, StringBuilder markdown) {
        JsonNode textNode = block.has("text") ? block.get("text") : null;
        if (textNode == null) {
            return;
        }

        String text = extractTextContent(textNode);
        if (!text.isEmpty()) {
            markdown.append(text).append("\n\n");
        }
    }

    private void convertHeadingBlock(JsonNode block, StringBuilder markdown, int level) {
        String headingKey = "heading" + level;
        JsonNode headingNode = block.has(headingKey) ? block.get(headingKey) : null;

        if (headingNode == null) {
            // 尝试从 text 字段获取
            headingNode = block.has("text") ? block.get("text") : null;
        }

        if (headingNode == null) {
            return;
        }

        String text = extractTextContent(headingNode);
        if (!text.isEmpty()) {
            // Markdown 最多支持 6 级标题
            int mdLevel = Math.min(level, 6);
            markdown.append("#".repeat(mdLevel)).append(" ").append(text).append("\n\n");
        }
    }

    private void convertBulletListBlock(JsonNode block, StringBuilder markdown, int depth) {
        JsonNode bulletNode = block.has("bullet") ? block.get("bullet") : null;
        if (bulletNode == null) {
            return;
        }

        String indent = "  ".repeat(depth);
        String text = extractTextContent(bulletNode);
        if (!text.isEmpty()) {
            markdown.append(indent).append("- ").append(text).append("\n");
        }

        if (block.has("children")) {
            convertBlocks(block.get("children"), markdown, depth + 1);
        }
    }

    private void convertOrderedListBlock(JsonNode block, StringBuilder markdown, int depth) {
        JsonNode orderedNode = block.has("ordered") ? block.get("ordered") : null;
        if (orderedNode == null) {
            return;
        }

        String indent = "  ".repeat(depth);
        String text = extractTextContent(orderedNode);
        if (!text.isEmpty()) {
            markdown.append(indent).append("1. ").append(text).append("\n");
        }

        if (block.has("children")) {
            convertBlocks(block.get("children"), markdown, depth + 1);
        }
    }

    private void convertCodeBlock(JsonNode block, StringBuilder markdown) {
        JsonNode codeNode = block.has("code") ? block.get("code") : null;
        if (codeNode == null) {
            return;
        }

        String language = "";
        if (codeNode.has("style") && codeNode.get("style").has("language")) {
            int langCode = codeNode.get("style").get("language").asInt();
            language = mapLanguageCode(langCode);
        }

        String text = extractTextContent(codeNode);
        markdown.append("```").append(language).append("\n");
        markdown.append(text).append("\n");
        markdown.append("```\n\n");
    }

    private void convertQuoteBlock(JsonNode block, StringBuilder markdown) {
        JsonNode quoteNode = block.has("quote") ? block.get("quote") : null;
        if (quoteNode == null) {
            return;
        }

        String text = extractTextContent(quoteNode);
        if (!text.isEmpty()) {
            String[] lines = text.split("\n");
            for (String line : lines) {
                markdown.append("> ").append(line).append("\n");
            }
            markdown.append("\n");
        }
    }

    private void convertTodoBlock(JsonNode block, StringBuilder markdown) {
        JsonNode todoNode = block.has("todo") ? block.get("todo") : null;
        if (todoNode == null) {
            return;
        }

        boolean checked = todoNode.has("style") &&
                todoNode.get("style").has("done") &&
                todoNode.get("style").get("done").asBoolean();

        String text = extractTextContent(todoNode);
        String checkbox = checked ? "[x]" : "[ ]";
        markdown.append("- ").append(checkbox).append(" ").append(text).append("\n");
    }

    private void convertImageBlock(JsonNode block, StringBuilder markdown) {
        JsonNode imageNode = block.has("image") ? block.get("image") : null;
        if (imageNode == null) {
            markdown.append("[图片]\n\n");
            return;
        }

        String token = imageNode.has("token") ? imageNode.get("token").asText() : "";
        String alt = "image";

        if (!token.isEmpty()) {
            // 飞书图片需要通过 API 获取，这里先用占位符
            markdown.append("![").append(alt).append("](feishu://image/").append(token).append(")\n\n");
        } else {
            markdown.append("[图片]\n\n");
        }
    }

    private void convertTableBlock(JsonNode block, StringBuilder markdown) {
        JsonNode tableNode = block.has("table") ? block.get("table") : null;
        if (tableNode == null) {
            return;
        }

        if (!block.has("children") || !block.get("children").isArray()) {
            return;
        }

        JsonNode rows = block.get("children");
        List<List<String>> tableData = new ArrayList<>();

        for (JsonNode row : rows) {
            if (row.has("children") && row.get("children").isArray()) {
                List<String> rowData = new ArrayList<>();
                for (JsonNode cell : row.get("children")) {
                    String cellText = "";
                    if (cell.has("children") && cell.get("children").isArray()) {
                        StringBuilder cellContent = new StringBuilder();
                        for (JsonNode cellBlock : cell.get("children")) {
                            if (cellBlock.has("text")) {
                                cellContent.append(extractTextContent(cellBlock.get("text")));
                            }
                        }
                        cellText = cellContent.toString().replace("|", "\\|").replace("\n", " ");
                    }
                    rowData.add(cellText);
                }
                tableData.add(rowData);
            }
        }

        if (tableData.isEmpty()) {
            return;
        }

        // 输出表格
        int colCount = tableData.stream().mapToInt(List::size).max().orElse(0);

        for (int i = 0; i < tableData.size(); i++) {
            List<String> row = tableData.get(i);
            markdown.append("|");
            for (int j = 0; j < colCount; j++) {
                String cell = j < row.size() ? row.get(j) : "";
                markdown.append(" ").append(cell).append(" |");
            }
            markdown.append("\n");

            // 添加表头分隔行
            if (i == 0) {
                markdown.append("|");
                for (int j = 0; j < colCount; j++) {
                    markdown.append(" --- |");
                }
                markdown.append("\n");
            }
        }
        markdown.append("\n");
    }

    private void convertCalloutBlock(JsonNode block, StringBuilder markdown) {
        JsonNode calloutNode = block.has("callout") ? block.get("callout") : null;
        if (calloutNode == null) {
            if (block.has("children")) {
                markdown.append("> ");
                convertBlocks(block.get("children"), markdown, 0);
            }
            return;
        }

        // Callout 转为引用块
        markdown.append("> ");
        if (block.has("children")) {
            for (JsonNode child : block.get("children")) {
                if (child.has("text")) {
                    markdown.append(extractTextContent(child.get("text")));
                }
            }
        }
        markdown.append("\n\n");
    }

    private String extractTextContent(JsonNode textNode) {
        if (textNode == null) {
            return "";
        }

        // 处理 elements 数组
        if (textNode.has("elements") && textNode.get("elements").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode element : textNode.get("elements")) {
                sb.append(extractElementText(element));
            }
            return sb.toString();
        }

        // 直接文本内容
        if (textNode.isTextual()) {
            return textNode.asText();
        }

        return "";
    }

    private String extractElementText(JsonNode element) {
        if (element == null) {
            return "";
        }

        // Text run
        if (element.has("text_run")) {
            JsonNode textRun = element.get("text_run");
            String content = textRun.has("content") ? textRun.get("content").asText() : "";

            // 处理文本样式
            if (textRun.has("text_element_style")) {
                JsonNode style = textRun.get("text_element_style");
                if (style.has("bold") && style.get("bold").asBoolean()) {
                    content = "**" + content + "**";
                }
                if (style.has("italic") && style.get("italic").asBoolean()) {
                    content = "*" + content + "*";
                }
                if (style.has("strikethrough") && style.get("strikethrough").asBoolean()) {
                    content = "~~" + content + "~~";
                }
                if (style.has("inline_code") && style.get("inline_code").asBoolean()) {
                    content = "`" + content + "`";
                }
                if (style.has("link") && style.get("link").has("url")) {
                    String url = style.get("link").get("url").asText();
                    content = "[" + content + "](" + url + ")";
                }
            }
            return content;
        }

        // Mention user
        if (element.has("mention_user")) {
            JsonNode mention = element.get("mention_user");
            return "@" + (mention.has("user_id") ? mention.get("user_id").asText() : "user");
        }

        // Mention doc
        if (element.has("mention_doc")) {
            JsonNode mention = element.get("mention_doc");
            String title = mention.has("title") ? mention.get("title").asText() : "文档";
            String token = mention.has("token") ? mention.get("token").asText() : "";
            return "[" + title + "](feishu://doc/" + token + ")";
        }

        // Equation
        if (element.has("equation")) {
            JsonNode equation = element.get("equation");
            String content = equation.has("content") ? equation.get("content").asText() : "";
            return "$" + content + "$";
        }

        return "";
    }

    private String mapLanguageCode(int code) {
        return switch (code) {
            case 1 -> "plaintext";
            case 2 -> "abap";
            case 3 -> "ada";
            case 4 -> "apache";
            case 5 -> "apex";
            case 6 -> "assembly";
            case 7 -> "bash";
            case 8 -> "csharp";
            case 9 -> "cpp";
            case 10 -> "c";
            case 11 -> "cobol";
            case 12 -> "css";
            case 13 -> "coffeescript";
            case 14 -> "d";
            case 15 -> "dart";
            case 16 -> "delphi";
            case 17 -> "django";
            case 18 -> "dockerfile";
            case 19 -> "erlang";
            case 20 -> "fortran";
            case 21 -> "foxpro";
            case 22 -> "go";
            case 23 -> "groovy";
            case 24 -> "html";
            case 25 -> "htmlbars";
            case 26 -> "http";
            case 27 -> "haskell";
            case 28 -> "json";
            case 29 -> "java";
            case 30 -> "javascript";
            case 31 -> "julia";
            case 32 -> "kotlin";
            case 33 -> "latex";
            case 34 -> "lisp";
            case 35 -> "lua";
            case 36 -> "matlab";
            case 37 -> "makefile";
            case 38 -> "markdown";
            case 39 -> "nginx";
            case 40 -> "objectivec";
            case 41 -> "openedge";
            case 42 -> "php";
            case 43 -> "perl";
            case 44 -> "postscript";
            case 45 -> "powershell";
            case 46 -> "prolog";
            case 47 -> "protobuf";
            case 48 -> "python";
            case 49 -> "r";
            case 50 -> "rpm";
            case 51 -> "ruby";
            case 52 -> "rust";
            case 53 -> "sas";
            case 54 -> "scss";
            case 55 -> "sql";
            case 56 -> "scala";
            case 57 -> "scheme";
            case 58 -> "scratch";
            case 59 -> "shell";
            case 60 -> "swift";
            case 61 -> "thrift";
            case 62 -> "typescript";
            case 63 -> "vbscript";
            case 64 -> "vbnet";
            case 65 -> "verilog";
            case 66 -> "vhdl";
            case 67 -> "xml";
            case 68 -> "yaml";
            default -> "";
        };
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class DocumentMetadata {
        private String title;
        private String documentId;
        private String version;
        private String publishDate;
        private String owner;
        private String applicableScope;
        private String tags;
        private String profile;
        private String tenant;
        private String scene;
        private Map<String, Object> extra;
    }
}
