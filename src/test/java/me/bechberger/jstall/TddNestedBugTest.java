package me.bechberger.jstall;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests TDD enforcement for @Nested classes.
 * The outer class exists in state. The inner class is new.
 * Both have a method called testSomething.
 */
public class TddNestedBugTest {

    @Test
    public void testSomething() {
        // This method is in the outer class - should be in state after a learn run
        assertTrue(true);
    }

    @Nested
    class InnerNewTest {
        @Test
        public void testSomething() {
            // New method in new inner class with SAME NAME as outer
            // Should trigger TDD but might not due to the fallback logic
            assertTrue(true);
        }
        
        @Test
        public void testUniqueInnerMethod() {
            // New method with unique name - should definitely trigger TDD
            assertTrue(true);
        }
    }
}
