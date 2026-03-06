package me.bechberger.jstall.provider.requirement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JcmdRequirementTest {

    @Test
    void resolveMBeanCommand_mapsVmSystemProperties() {
        assertEquals("vmSystemProperties", JcmdRequirement.resolveMBeanCommand("VM.system_properties"));
    }

    @Test
    void resolveMBeanCommand_mapsUnknownCommand() {
        assertEquals("someCustomCommand", JcmdRequirement.resolveMBeanCommand("Some.custom_command"));
    }
}
