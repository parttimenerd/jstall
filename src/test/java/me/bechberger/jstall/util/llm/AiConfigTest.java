package me.bechberger.jstall.util.llm;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiConfigTest {

    @Test
    void testOllamaThinkParsing() throws Exception {
        Properties p = new Properties();
        p.setProperty("provider", "ollama");
        p.setProperty("model", "gpt-oss:latest");
        p.setProperty("ollama.host", "http://127.0.0.1:11434");
        p.setProperty("ollama.think", "medium");

        AiConfig cfg = fromProperties(p);
        assertThat(cfg.ollamaThinkMode()).isEqualTo(AiConfig.OllamaThinkMode.MEDIUM);
        assertThat(cfg.getEffectiveOllamaThinkMode()).isEqualTo(AiConfig.OllamaThinkMode.MEDIUM);
    }

    @Test
    void testOllamaThinkDefaultForGptOssIsHigh() throws Exception {
        Properties p = new Properties();
        p.setProperty("provider", "ollama");
        p.setProperty("model", "gpt-oss:latest");

        AiConfig cfg = fromProperties(p);
        assertThat(cfg.ollamaThinkMode()).isEqualTo(AiConfig.OllamaThinkMode.HIGH);
        assertThat(cfg.getEffectiveOllamaThinkMode()).isEqualTo(AiConfig.OllamaThinkMode.HIGH);
    }

    @Test
    void testOllamaThinkDefaultForOtherModelsIsHigh() throws Exception {
        Properties p = new Properties();
        p.setProperty("provider", "ollama");
        p.setProperty("model", "qwen3:30b");

        AiConfig cfg = fromProperties(p);
        assertThat(cfg.ollamaThinkMode()).isEqualTo(AiConfig.OllamaThinkMode.HIGH);
        assertThat(cfg.getEffectiveOllamaThinkMode()).isEqualTo(AiConfig.OllamaThinkMode.HIGH);
    }

    @Test
    void testOllamaThinkBooleanMappingForGptOss() throws Exception {
        Properties pTrue = new Properties();
        pTrue.setProperty("provider", "ollama");
        pTrue.setProperty("model", "gpt-oss:latest");
        pTrue.setProperty("ollama.think", "true");
        AiConfig cfgTrue = fromProperties(pTrue);
        assertThat(cfgTrue.getEffectiveOllamaThinkMode()).isEqualTo(AiConfig.OllamaThinkMode.HIGH);

        Properties pFalse = new Properties();
        pFalse.setProperty("provider", "ollama");
        pFalse.setProperty("model", "gpt-oss:latest");
        pFalse.setProperty("ollama.think", "false");
        AiConfig cfgFalse = fromProperties(pFalse);
        assertThat(cfgFalse.getEffectiveOllamaThinkMode()).isEqualTo(AiConfig.OllamaThinkMode.LOW);
    }

    @Test
    void testOllamaThinkInvalidValue() {
        Properties p = new Properties();
        p.setProperty("provider", "ollama");
        p.setProperty("model", "qwen3:30b");
        p.setProperty("ollama.think", "nope");

        assertThatThrownBy(() -> fromProperties(p))
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .rootCause()
            .hasMessageContaining("Invalid ollama.think value");
    }

    private static AiConfig fromProperties(Properties props) throws Exception {
        Method m = AiConfig.class.getDeclaredMethod("fromProperties", Properties.class);
        m.setAccessible(true);
        return (AiConfig) m.invoke(null, props);
    }
}