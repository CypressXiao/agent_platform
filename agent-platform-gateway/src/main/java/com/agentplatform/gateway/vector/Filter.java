package com.agentplatform.gateway.vector;

/**
 * Vector Store Filter
 * 用于向量搜索的过滤条件
 */
public class Filter {
    
    public static class Expression {
        private String key;
        private String operator;
        private Object value;
        
        public Expression() {}
        
        public Expression(String key, String operator, Object value) {
            this.key = key;
            this.operator = operator;
            this.value = value;
        }
        
        // Getters and Setters
        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getOperator() { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
    }
    
    public static class And {
        private Expression[] expressions;
        
        public And() {}
        
        public And(Expression... expressions) {
            this.expressions = expressions;
        }
        
        public Expression[] getExpressions() { return expressions; }
        public void setExpressions(Expression[] expressions) { this.expressions = expressions; }
    }
    
    public static class Or {
        private Expression[] expressions;
        
        public Or() {}
        
        public Or(Expression... expressions) {
            this.expressions = expressions;
        }
        
        public Expression[] getExpressions() { return expressions; }
        public void setExpressions(Expression[] expressions) { this.expressions = expressions; }
    }
}
