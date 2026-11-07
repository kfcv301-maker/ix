package com.admin.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AirportObserverProgramTest {
    @Test void fixedPythonWrapperParsesWithoutExecutingAnything() throws Exception {
        Process process=new ProcessBuilder(System.getProperty("os.name").startsWith("Windows")?"python":"python3","-c","import ast,sys; ast.parse(sys.stdin.read())").start();
        process.getOutputStream().write(VpsSshServiceImpl.observerProgram("cGFzcw==","a".repeat(64)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        process.getOutputStream().close();assertEquals(0,process.waitFor());
    }
    @Test void fixedTemplateChecksExistingIdentityBeforeInstallingAndCannotInjectShell() {
        String p=VpsSshServiceImpl.observerProgram("cGFzcw==","a".repeat(64));
        assertTrue(p.indexOf("hmac.compare_digest")<p.indexOf("module['install']"));
        assertTrue(p.contains("fcntl.LOCK_EX|fcntl.LOCK_NB"));
        assertFalse(p.contains("systemctl restart"));assertFalse(p.contains("backend_install"));
        assertThrows(IllegalArgumentException.class,()->VpsSshServiceImpl.observerProgram("a'\n","a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->VpsSshServiceImpl.observerProgram("cGFzcw==","x"));
    }
}
