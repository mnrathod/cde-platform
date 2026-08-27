package com.cde.platform.config;

import com.cde.platform.model.*;
import com.cde.platform.repository.*;
import com.cde.platform.tenancy.TenancyProperties;
import com.cde.platform.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Demonstration data for a deployment that has asked for it.
 *
 * <p>This used to run unconditionally and create two accounts whose passwords
 * were written here in the source. See {@link SeedProperties} for why it no
 * longer does either.
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    @Value("${cde.storage.upload-dir}")
    private String uploadDir;

    @Bean
    CommandLineRunner seed(UserRepository userRepo, ProjectRepository projectRepo,
                           DocumentRepository documentRepo, PasswordEncoder encoder,
                           TenantRepository tenantRepo, TenancyProperties tenancyProperties,
                           SeedProperties seedProperties) {
        return args -> {
            if (!seedProperties.isEnabled()) {
                log.info("Seeding is disabled (cde.seed.enabled=false); "
                       + "the first organisation is created by registering an account.");
                return;
            }
            // Seeding is background work, so it must establish tenant context
            // explicitly (§5.6). Without it every read returns nothing — the
            // count below would report zero on a populated database and seed a
            // duplicate set — and every write is rejected by the RLS WITH CHECK
            // clause.
            Long defaultTenantId = tenantRepo.findBySlug(tenancyProperties.getDefaultTenantSlug())
                .orElseThrow(() -> new IllegalStateException(
                    "Default tenant '" + tenancyProperties.getDefaultTenantSlug()
                    + "' is missing; V2__tenant_isolation.sql creates it"))
                .getId();

            TenantContext.runAsTenant(defaultTenantId, () -> seedDefaultTenant(
                userRepo, projectRepo, documentRepo, encoder, seedProperties));
        };
    }

    private void seedDefaultTenant(UserRepository userRepo, ProjectRepository projectRepo,
                                   DocumentRepository documentRepo, PasswordEncoder encoder,
                                   SeedProperties seedProperties) {
        try {
            if (userRepo.count() > 0) return;

            // One account, not two. The second existed only so a sample
            // project could have a different owner, which cost a second
            // credential to maintain and to keep out of the repository. A
            // deployment that wants a second role now invites one — which is
            // the mechanism a real tenant uses, so it demonstrates the product
            // rather than working around it.
            var admin = User.builder()
                .username(seedProperties.getAdminUsername())
                .email(seedProperties.getAdminEmail())
                .password(encoder.encode(seedProperties.getAdminPassword()))
                .role(User.Role.ADMIN).build();
            userRepo.save(admin);
            log.info("Seeded the demonstration administrator '{}'. "
                   + "Its password is the configured cde.seed.admin-password and is not logged.",
                     seedProperties.getAdminUsername());

            var proj1 = Project.builder()
                .name("City Bridge Expansion")
                .description("Structural expansion of main city bridge — Phase 2")
                .location("Downtown, Sector 7")
                .phase(Project.ProjectPhase.DESIGN)
                .owner(admin).build();
            var proj2 = Project.builder()
                .name("Metro Station Retrofit")
                .description("Seismic retrofit and accessibility upgrades")
                .location("Station Central")
                .phase(Project.ProjectPhase.CONSTRUCTION)
                .owner(admin).build();
            projectRepo.save(proj1);
            projectRepo.save(proj2);

            // Sample SVG drawing for 2D viewer demo
            String svgDrawing = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 600" width="800" height="600">
                  <defs>
                    <pattern id="grid" width="50" height="50" patternUnits="userSpaceOnUse">
                      <path d="M 50 0 L 0 0 0 50" fill="none" stroke="#ddd" stroke-width="0.5"/>
                    </pattern>
                  </defs>
                  <rect width="100%" height="100%" fill="white"/>
                  <rect width="100%" height="100%" fill="url(#grid)"/>
                  <!-- Title block -->
                  <rect x="10" y="10" width="780" height="580" fill="none" stroke="#000" stroke-width="2"/>
                  <rect x="520" y="510" width="270" height="70" fill="none" stroke="#000" stroke-width="1"/>
                  <text x="655" y="530" text-anchor="middle" font-size="10" font-family="Arial">CITY BRIDGE EXPANSION</text>
                  <text x="655" y="545" text-anchor="middle" font-size="8" font-family="Arial">STRUCTURAL PLAN - LEVEL 1</text>
                  <text x="655" y="560" text-anchor="middle" font-size="8" font-family="Arial">DWG: CBE-ST-001  REV: A</text>
                  <!-- Bridge outline -->
                  <rect x="60" y="100" width="640" height="340" fill="#f0f4ff" stroke="#333" stroke-width="2"/>
                  <!-- Deck -->
                  <rect x="60" y="180" width="640" height="100" fill="#dde6ff" stroke="#333" stroke-width="1.5"/>
                  <text x="380" y="237" text-anchor="middle" font-size="12" font-family="Arial" fill="#333">BRIDGE DECK</text>
                  <!-- Left abutment -->
                  <rect x="60" y="100" width="80" height="340" fill="#c5d0e6" stroke="#333" stroke-width="1.5"/>
                  <text x="100" y="275" text-anchor="middle" font-size="9" font-family="Arial" fill="#333" transform="rotate(-90,100,275)">ABUTMENT W</text>
                  <!-- Right abutment -->
                  <rect x="620" y="100" width="80" height="340" fill="#c5d0e6" stroke="#333" stroke-width="1.5"/>
                  <text x="660" y="275" text-anchor="middle" font-size="9" font-family="Arial" fill="#333" transform="rotate(90,660,275)">ABUTMENT E</text>
                  <!-- Piers -->
                  <rect x="230" y="280" width="60" height="160" fill="#b0bfd6" stroke="#333" stroke-width="1.5"/>
                  <text x="260" y="367" text-anchor="middle" font-size="9" font-family="Arial">P1</text>
                  <rect x="510" y="280" width="60" height="160" fill="#b0bfd6" stroke="#333" stroke-width="1.5"/>
                  <text x="540" y="367" text-anchor="middle" font-size="9" font-family="Arial">P2</text>
                  <!-- Dimensions -->
                  <line x1="60" y1="470" x2="310" y2="470" stroke="#e00" stroke-width="1" marker-end="url(#arrow)"/>
                  <line x1="570" y1="470" x2="700" y2="470" stroke="#e00" stroke-width="1"/>
                  <text x="380" y="475" text-anchor="middle" font-size="9" fill="#e00">↔ 640m TOTAL SPAN ↔</text>
                  <!-- North arrow -->
                  <circle cx="470" cy="460" r="20" fill="none" stroke="#333" stroke-width="1"/>
                  <polygon points="470,442 464,472 470,466 476,472" fill="#333"/>
                  <text x="470" y="488" text-anchor="middle" font-size="9" font-family="Arial">N</text>
                </svg>
                """;

            // Write the seeded drawing to a real file, exactly like DocumentController.upload()
            // does for a normal upload — otherwise this document has no filePath and any
            // feature that reads the file from disk (e.g. digital signing) breaks on it.
            Path projectDir = Paths.get(uploadDir, proj1.getId().toString());
            Files.createDirectories(projectDir);
            String storedName = UUID.randomUUID() + "_CBE-ST-001-RevA.svg";
            Path filePath = projectDir.resolve(storedName);
            Files.writeString(filePath, svgDrawing, StandardCharsets.UTF_8);

            var doc1 = Document.builder()
                .name("Structural Plan Level 1").description("Ground floor structural layout")
                .fileName("CBE-ST-001-RevA.svg").fileType("image/svg+xml").fileSize((long) svgDrawing.length())
                .documentType(Document.DocumentType.DRAWING).revision("A").drawingNumber("CBE-ST-001")
                .filePath(filePath.toString())
                .vectorData(svgDrawing).project(proj1).uploadedBy(admin).build();

            documentRepo.save(doc1);
        } catch (IOException e) {
            // Seeding runs inside a CommandLineRunner, so a checked exception
            // cannot propagate from the Runnable. Failing loudly is right: a
            // seeded document whose file never reached disk has no filePath,
            // and every feature that reads the file — digital signing among
            // them — breaks on it later, far from the cause.
            throw new IllegalStateException("Failed to write the seed drawing to disk", e);
        }
    }
}
