package net.agentensemble.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CalculatorToolTest {

    private CalculatorTool tool;

    @BeforeEach
    void setUp() {
        tool = new CalculatorTool();
    }

    // --- metadata ---

    @Test
    void name_returnsCalculator() {
        assertThat(tool.name()).isEqualTo("calculator");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- basic arithmetic ---

    @Test
    void execute_addition() {
        assertThat(tool.execute("2 + 3").getOutput()).isEqualTo("5");
    }

    @Test
    void execute_subtraction() {
        assertThat(tool.execute("10 - 4").getOutput()).isEqualTo("6");
    }

    @Test
    void execute_multiplication() {
        assertThat(tool.execute("3 * 7").getOutput()).isEqualTo("21");
    }

    @Test
    void execute_division() {
        assertThat(tool.execute("20 / 4").getOutput()).isEqualTo("5");
    }

    @Test
    void execute_modulo() {
        assertThat(tool.execute("17 % 5").getOutput()).isEqualTo("2");
    }

    @Test
    void execute_power() {
        assertThat(tool.execute("2 ^ 10").getOutput()).isEqualTo("1024");
    }

    // --- operator precedence ---

    @Test
    void execute_multiplicationBeforeAddition() {
        assertThat(tool.execute("2 + 3 * 4").getOutput()).isEqualTo("14");
    }

    @Test
    void execute_divisionBeforeSubtraction() {
        assertThat(tool.execute("10 - 8 / 4").getOutput()).isEqualTo("8");
    }

    // --- parentheses ---

    @Test
    void execute_parenthesesOverridePrecedence() {
        assertThat(tool.execute("(2 + 3) * 4").getOutput()).isEqualTo("20");
    }

    @Test
    void execute_nestedParentheses() {
        assertThat(tool.execute("((2 + 3) * (4 - 1))").getOutput()).isEqualTo("15");
    }

    // --- unary minus ---

    @Test
    void execute_unaryMinus() {
        assertThat(tool.execute("-5 + 10").getOutput()).isEqualTo("5");
    }

    @Test
    void execute_unaryMinusInParentheses() {
        assertThat(tool.execute("(-3) * 4").getOutput()).isEqualTo("-12");
    }

    // --- decimals ---

    @Test
    void execute_decimalNumbers() {
        assertThat(tool.execute("1.5 + 2.5").getOutput()).isEqualTo("4");
    }

    @Test
    void execute_decimalResult() {
        assertThat(tool.execute("1 / 3").isSuccess()).isTrue();
        assertThat(tool.execute("1 / 3").getOutput()).startsWith("0.");
    }

    // --- whitespace ---

    @Test
    void execute_extraWhitespace() {
        assertThat(tool.execute("  2  +  3  ").getOutput()).isEqualTo("5");
    }

    @Test
    void execute_noWhitespace() {
        assertThat(tool.execute("2+3*4").getOutput()).isEqualTo("14");
    }

    // --- complex expressions ---

    @Test
    void execute_complexExpression() {
        assertThat(tool.execute("(10 + 5) * 2 - 3").getOutput()).isEqualTo("27");
    }

    @Test
    void execute_chainedOperations() {
        assertThat(tool.execute("1 + 2 + 3 + 4 + 5").getOutput()).isEqualTo("15");
    }

    // --- failure cases ---

    @Test
    void execute_divisionByZero_returnsFailure() {
        var result = tool.execute("5 / 0");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("division by zero");
    }

    @Test
    void execute_moduloByZero_returnsFailure() {
        var result = tool.execute("5 % 0");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("zero");
    }

    @Test
    void execute_nullInput_returnsFailure() {
        var result = tool.execute(null);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_blankInput_returnsFailure() {
        var result = tool.execute("  ");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_invalidExpression_returnsFailure() {
        var result = tool.execute("abc + 1");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_unclosedParenthesis_returnsFailure() {
        var result = tool.execute("(2 + 3");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_extraCharacters_returnsFailure() {
        var result = tool.execute("2 + 3 foo");
        assertThat(result.isSuccess()).isFalse();
    }
}
