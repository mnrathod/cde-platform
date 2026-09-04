package com.cde.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * That a conversion job asks the converter for a file, not for a picture.
 *
 * <p>One endpoint serves two callers with opposite needs: the viewer wants a
 * drawing as SVG it can render and mark up, and a job wants a PDF. The
 * converter cannot tell them apart from the file alone — a DXF is a legitimate
 * input to both — so the request has to say which, and the failure when it did
 * not was silent: the job asked for a PDF, received a rendered SVG, and
 * reported that the file could not be converted. It had converted.
 *
 * <p>Driven against a stub HTTP server rather than a mock so the assertion is
 * on the bytes actually sent, which is where the defect was.
 */
class ConverterPdfRequestTest {

    private HttpServer server;
    private ConverterService converter;
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private byte[] responseBody = new byte[0];
    private String responseContentType = "application/pdf";

    @BeforeEach
    void startStubConverter() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/convert", exchange -> {
            lastRequest.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().add("Content-Type", responseContentType);
            exchange.sendResponseHeaders(200, responseBody.length);
            exchange.getResponseBody().write(responseBody);
            exchange.close();
        });
        server.start();
        converter = new ConverterService(
            "http://127.0.0.1:" + server.getAddress().getPort(), null);
    }

    @AfterEach
    void stopStubConverter() {
        server.stop(0);
    }

    @Test
    @DisplayName("a streamed conversion names PDF as the target format")
    void streamedConversionAsksForPdf(@org.junit.jupiter.api.io.TempDir Path dir)
            throws IOException {
        responseBody = "%PDF-1.7\nstub".getBytes(StandardCharsets.UTF_8);
        Path source = Files.writeString(dir.resolve("plan.dxf"), "0\nSECTION\n");

        converter.convertToPdfFile(source, "application/dxf",
            dir.resolve("out.pdf"), Duration.ofSeconds(10));

        assertThat(lastRequest.get().path("targetFormat").asText())
            .as("without this the converter renders the viewer's SVG")
            .isEqualTo("PDF");
    }

    @Test
    @DisplayName("the in-memory conversion names it too")
    void inMemoryConversionAsksForPdf(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        responseBody = "%PDF-1.7\nstub".getBytes(StandardCharsets.UTF_8);
        Path source = Files.writeString(dir.resolve("plan.dxf"), "0\nSECTION\n");

        converter.convertToPdf(source, "application/dxf");

        assertThat(lastRequest.get().path("targetFormat").asText()).isEqualTo("PDF");
    }

    @Test
    @DisplayName("the viewer's own call does not, so it still gets SVG")
    void theViewerCallIsUnchanged(@org.junit.jupiter.api.io.TempDir Path dir)
            throws IOException {
        // The two callers must stay distinguishable. If this one starts asking
        // for PDF the viewer renders nothing, which is the same defect facing
        // the other way.
        responseContentType = "application/json";
        responseBody = "{\"success\":true,\"svg\":\"<svg/>\"}".getBytes(StandardCharsets.UTF_8);
        Path source = Files.writeString(dir.resolve("plan.dxf"), "0\nSECTION\n");

        converter.convert(source, "plan.dxf");

        assertThat(lastRequest.get().has("targetFormat"))
            .as("the viewer must not be handed a PDF it cannot draw")
            .isFalse();
    }

    @Test
    @DisplayName("a refusal reaches the submitter as the converter's own words")
    void aRefusalKeepsItsReason(@org.junit.jupiter.api.io.TempDir Path dir)
            throws IOException {
        responseContentType = "application/json";
        responseBody = ("{\"success\":false,\"error\":\"A 3D model has no defined PDF "
            + "form. Use the model tree endpoint.\"}").getBytes(StandardCharsets.UTF_8);
        Path source = Files.writeString(dir.resolve("tower.ifc"), "ISO-10303-21;\n");

        assertThatThrownBy(() -> converter.convertToPdfFile(source, "application/ifc",
                dir.resolve("out.pdf"), Duration.ofSeconds(10)))
            .isInstanceOf(ConverterService.ConversionRefusedException.class)
            .hasMessageContaining("no defined PDF form")
            .hasMessageContaining("model tree");
    }

    @Test
    @DisplayName("a non-PDF success says so rather than blaming the file")
    void anUnexpectedTypeIsNotReportedAsAnUnconvertibleFile(
            @org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        // This is the shape the old defect arrived in: success, no error, and
        // a payload that is not a PDF. Telling the submitter their file could
        // not be converted sends them to look at a file that is fine.
        responseContentType = "application/json";
        responseBody = "{\"success\":true,\"type\":\"svg\"}".getBytes(StandardCharsets.UTF_8);
        Path source = Files.writeString(dir.resolve("plan.dxf"), "0\nSECTION\n");

        assertThatThrownBy(() -> converter.convertToPdfFile(source, "application/dxf",
                dir.resolve("out.pdf"), Duration.ofSeconds(10)))
            .isInstanceOf(ConverterService.ConversionRefusedException.class)
            .hasMessageContaining("svg")
            .hasMessageNotContaining("could not convert that file");
    }

    @Test
    @DisplayName("a failed conversion leaves no partial file behind")
    void aRefusalLeavesNothingOnDisk(@org.junit.jupiter.api.io.TempDir Path dir)
            throws IOException {
        responseContentType = "application/json";
        responseBody = "{\"success\":false,\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
        Path source = Files.writeString(dir.resolve("plan.dxf"), "0\nSECTION\n");
        Path destination = dir.resolve("out.pdf");

        assertThatThrownBy(() -> converter.convertToPdfFile(source, "application/dxf",
            destination, Duration.ofSeconds(10)))
            .isInstanceOf(ConverterService.ConversionRefusedException.class);

        assertThat(Files.exists(destination))
            .as("a truncated file is one the next step cannot tell from a document")
            .isFalse();
    }
}
