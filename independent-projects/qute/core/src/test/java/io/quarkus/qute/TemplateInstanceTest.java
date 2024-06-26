package io.quarkus.qute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

public class TemplateInstanceTest {

    @Test
    public void testInitializer() {
        Engine engine = Engine.builder()
                .addDefaults()
                .addTemplateInstanceInitializer(instance -> instance.data("foo", "bar").setAttribute("alpha", Boolean.TRUE))
                .build();

        Template hello = engine.parse("Hello {foo}!");
        TemplateInstance instance = hello.instance();
        assertEquals(Boolean.TRUE, instance.getAttribute("alpha"));
        assertEquals("Hello bar!", instance.render());
        instance.data("foo", "baz");
        assertEquals("Hello baz!", instance.render());
    }

    @Test
    public void testRendered() {
        Engine engine = Engine.builder().addDefaults().build();
        Template hello = engine.parse("Hello {foo}!");
        AtomicBoolean rendered = new AtomicBoolean();
        TemplateInstance instance = hello.instance().data("foo", "baz").onRendered(() -> rendered.set(true));
        assertEquals("Hello baz!", instance.render());
        assertTrue(rendered.get());
    }

    @Test
    public void testGetTemplate() {
        Engine engine = Engine.builder().addDefaults().build();
        Template hello = engine.parse("Hello {foo}!");
        String generatedId = hello.getGeneratedId();
        assertEquals(generatedId, hello.instance().getTemplate().getGeneratedId());
    }

    @Test
    public void testComputeData() {
        Engine engine = Engine.builder().addDefaults().build();
        TemplateInstance instance = engine.parse("Hello {foo} and {bar}!").instance();
        AtomicBoolean barUsed = new AtomicBoolean();
        AtomicBoolean fooUsed = new AtomicBoolean();
        instance
                .computedData("bar", key -> {
                    barUsed.set(true);
                    return key.length();
                })
                .data("bar", 30)
                .computedData("foo", key -> {
                    fooUsed.set(true);
                    return key.toUpperCase();
                });
        assertFalse(fooUsed.get());
        assertEquals("Hello FOO and 30!", instance.render());
        assertTrue(fooUsed.get());
        assertFalse(barUsed.get());
    }
}
