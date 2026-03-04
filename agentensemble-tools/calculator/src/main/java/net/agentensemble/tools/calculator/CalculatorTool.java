package net.agentensemble.tools.calculator;

import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Tool that evaluates arithmetic expressions.
 *
 * <p>Supports the standard four operations (+, -, *, /), modulo (%), exponentiation (^),
 * parentheses, unary minus, and decimal numbers. Operator precedence follows standard math
 * conventions: ^ before * / % before + -.
 *
 * <p>Input: a math expression string such as {@code "2 + 3 * 4"} or {@code "(10 + 5) / 3"}.
 *
 * <p>Output: the numeric result as a string. Integer results are formatted without a decimal
 * point; fractional results use standard double notation.
 */
public final class CalculatorTool extends AbstractAgentTool {

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "Evaluates arithmetic expressions. Supports +, -, *, /, % (modulo), ^ (power) "
                + "and parentheses. Input: a math expression such as '2 + 3 * 4' or '(10 + 5) / 3'.";
    }

    @Override
    protected ToolResult doExecute(String input) {
        if (input == null || input.isBlank()) {
            return ToolResult.failure("Expression must not be blank");
        }
        try {
            double result = new ExpressionParser(input.trim()).parse();
            return ToolResult.success(formatResult(result));
        } catch (ArithmeticException e) {
            return ToolResult.failure(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.failure("Invalid expression: " + e.getMessage());
        }
    }

    private static String formatResult(double result) {
        if (Double.isInfinite(result) || Double.isNaN(result)) {
            return String.valueOf(result);
        }
        if (result == Math.floor(result) && Math.abs(result) < 1e15) {
            return String.valueOf((long) result);
        }
        return String.valueOf(result);
    }

    /**
     * Recursive-descent arithmetic expression parser.
     *
     * <p>Grammar (lowest to highest precedence):
     *
     * <pre>
     * Expression := Term (('+' | '-') Term)*
     * Term       := Power (('*' | '/' | '%') Power)*
     * Power      := Unary ('^' Unary)?
     * Unary      := ('-' | '+') Unary | Primary
     * Primary    := Number | '(' Expression ')'
     * Number     := digit+ ('.' digit+)?
     * </pre>
     */
    private static final class ExpressionParser {

        private final String input;
        private int pos;

        ExpressionParser(String input) {
            this.input = input;
            this.pos = 0;
        }

        double parse() {
            double result = parseExpression();
            skipWhitespace();
            if (pos < input.length()) {
                throw new IllegalArgumentException(
                        "Unexpected character '" + input.charAt(pos) + "' at position " + pos);
            }
            return result;
        }

        private double parseExpression() {
            double left = parseTerm();
            while (true) {
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == '+') {
                    pos++;
                    left += parseTerm();
                } else if (pos < input.length() && input.charAt(pos) == '-') {
                    pos++;
                    left -= parseTerm();
                } else {
                    break;
                }
            }
            return left;
        }

        private double parseTerm() {
            double left = parsePower();
            while (true) {
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == '*') {
                    pos++;
                    left *= parsePower();
                } else if (pos < input.length() && input.charAt(pos) == '/') {
                    pos++;
                    double divisor = parsePower();
                    if (divisor == 0.0) {
                        throw new ArithmeticException("Division by zero");
                    }
                    left /= divisor;
                } else if (pos < input.length() && input.charAt(pos) == '%') {
                    pos++;
                    double divisor = parsePower();
                    if (divisor == 0.0) {
                        throw new ArithmeticException("Modulo by zero");
                    }
                    left %= divisor;
                } else {
                    break;
                }
            }
            return left;
        }

        private double parsePower() {
            double base = parseUnary();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '^') {
                pos++;
                double exponent = parseUnary();
                return Math.pow(base, exponent);
            }
            return base;
        }

        private double parseUnary() {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
                return -parsePrimary();
            }
            if (pos < input.length() && input.charAt(pos) == '+') {
                pos++;
            }
            return parsePrimary();
        }

        private double parsePrimary() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }
            char c = input.charAt(pos);
            if (c == '(') {
                pos++;
                double result = parseExpression();
                skipWhitespace();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
                pos++;
                return result;
            }
            if (Character.isDigit(c) || c == '.') {
                return parseNumber();
            }
            throw new IllegalArgumentException("Unexpected character '" + c + "'");
        }

        private double parseNumber() {
            int start = pos;
            while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                pos++;
            }
            String numStr = input.substring(start, pos);
            try {
                return Double.parseDouble(numStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number: " + numStr);
            }
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }
}
