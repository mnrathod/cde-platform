package com.cde.platform.controller;

import com.cde.platform.model.Document;
import com.cde.platform.model.Project;
import com.cde.platform.model.User;
import com.cde.platform.repository.DocumentRepository;
import com.cde.platform.repository.ProjectRepository;
import com.cde.platform.repository.UserRepository;
import com.cde.platform.support.ActingAs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Listing a project's documents, sorted and paged.
 *
 * <p>The sort parameter is the interesting one, and it is interesting for a
 * security reason rather than an ergonomic one. A sort field goes into an
 * {@code ORDER BY}, so it cannot be parameterised the way a value can — §5.12
 * A03 is explicit that dynamic ordering uses an allow-list of column names and
 * never user input interpolated into SQL. The allow-list is therefore the
 * control, and a test that only ever sorts by a permitted field never touches
 * it.
 *
 * <p>Paging matters for a different reason: §7.1's budget. A caller asking for
 * a page of fifty thousand rows is asking the deployment to spend its request
 * budget on them, so the size is clamped rather than honoured, and a negative
 * page is read as the first rather than refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActingAs(value = User.Role.ENGINEER, username = DocumentListingTest.USERNAME)
@DisplayName("listing a project's documents")
class DocumentListingTest {

    static final String USERNAME = "document-listing-user";

    @Autowired MockMvc mockMvc;
    @Autowired DocumentRepository documentRepo;
    @Autowired ProjectRepository projectRepo;
    @Autowired UserRepository userRepo;

    private Long projectId;
    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepo.findByUsername(USERNAME).orElseGet(() ->
            userRepo.save(User.builder()
                .username(USERNAME).email("document-listing@example.test")
                .password("{noop}irrelevant").role(User.Role.ENGINEER).build()));
        projectId = projectRepo.save(Project.builder()
            .name("Listing " + System.nanoTime())
            .phase(Project.ProjectPhase.DESIGN).build()).getId();
    }

    private Document document(String name) {
        return documentRepo.save(Document.builder()
            .name(name).fileName(name + ".pdf").fileType("application/pdf")
            .filePath("/not/read/by/this/endpoint/" + name + ".pdf").fileSize(1L)
            .documentType(Document.DocumentType.DRAWING)
            .project(projectRepo.findById(projectId).orElseThrow())
            .uploadedBy(owner).build());
    }

    private org.springframework.test.web.servlet.ResultActions list(String query)
            throws Exception {
        return mockMvc.perform(get("/api/documents/project/" + projectId + query));
    }

    @Nested
    @DisplayName("which fields may be sorted on")
    class SortAllowList {

        @ParameterizedTest(name = "sorting by {0} is permitted")
        @ValueSource(strings = {"name", "createdAt", "updatedAt", "fileSize",
                            "revision", "drawingNumber"})
        @DisplayName("a permitted field sorts")
        void permittedFieldsSort(String field) throws Exception {
            document("one");

            list("?sort=" + field).andExpect(status().isOk());
        }

        @Test
        @DisplayName("a field the model has but the list does not is refused")
        void fieldNotOnTheListIsRefused() throws Exception {
            // fileName is a real column and still refused, which is the
            // difference between an allow-list and a guess at what is safe.
            document("one");

            list("?sort=fileName").andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("a field that is not on the list is refused, not passed to the database")
        void unknownFieldIsRefused() throws Exception {
            // The allow-list is the control (§5.12 A03). A sort field reaches
            // ORDER BY, where it cannot be bound as a parameter, so anything
            // not vetted here is interpolated SQL.
            document("one");

            list("?sort=notAColumn").andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("a field spelling an injection attempt is refused like any other")
        void injectionAttemptIsRefused() throws Exception {
            // Nothing special is done to recognise this — it is refused because
            // it is not on the list, which is the point of an allow-list over a
            // deny-list.
            document("one");

            list("?sort=name;DROP%20TABLE%20documents").andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("the refusal names the field, so a client can fix its request")
        void refusalNamesTheField() throws Exception {
            document("one");

            String body = list("?sort=notAColumn").andReturn().getResponse().getContentAsString();

            assertThat(body).contains("notAColumn");
            // And what would have been accepted. Without the list a caller
            // cannot tell a typo from a field this version does not support,
            // which is what the generic handler's sentence left them with.
            assertThat(body).contains("createdAt");
        }

        @Test
        @DisplayName("descending order is honoured")
        void descendingIsHonoured() throws Exception {
            document("alpha");
            document("zulu");

            list("?sort=name,desc")
                .andExpect(jsonPath("$.content[0].name").value("zulu"));
        }

        @Test
        @DisplayName("ascending is the default when no direction is given")
        void ascendingIsTheDefault() throws Exception {
            document("alpha");
            document("zulu");

            list("?sort=name")
                .andExpect(jsonPath("$.content[0].name").value("alpha"));
        }

        @Test
        @DisplayName("the direction is read without regard to case")
        void directionIsCaseInsensitive() throws Exception {
            document("alpha");
            document("zulu");

            list("?sort=name,DESC")
                .andExpect(jsonPath("$.content[0].name").value("zulu"));
        }

        @Test
        @DisplayName("a direction that is not 'desc' sorts ascending rather than failing")
        void unknownDirectionSortsAscending() throws Exception {
            // A typo in the direction should not cost the request; the field is
            // the part that has to be vetted.
            document("alpha");
            document("zulu");

            list("?sort=name,sideways")
                .andExpect(jsonPath("$.content[0].name").value("alpha"));
        }

        @Test
        @DisplayName("surrounding whitespace in the field is forgiven")
        void whitespaceIsTrimmed() throws Exception {
            document("one");

            mockMvc.perform(get("/api/documents/project/" + projectId)
                    .param("sort", "  name  "))
                .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("how much comes back at once")
    class Paging {

        @Test
        @DisplayName("a page is returned, not a bare array")
        void returnsAPage() throws Exception {
            document("one");

            list("").andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.totalElements").exists());
        }

        @Test
        @DisplayName("the page size asked for is honoured")
        void honoursThePageSize() throws Exception {
            document("one");
            document("two");
            document("three");

            list("?size=2").andExpect(jsonPath("$.content.length()").value(2));
        }

        @Test
        @DisplayName("an enormous page size is clamped rather than served")
        void clampsALargePageSize() throws Exception {
            // §7.1: a caller asking for fifty thousand rows is asking the
            // deployment to spend its request budget on them. Clamping keeps
            // the endpoint inside a second without refusing the request.
            document("one");

            list("?size=50000").andExpect(status().isOk());
        }

        @Test
        @DisplayName("a page size of zero is raised to one rather than returning nothing")
        void clampsAZeroPageSize() throws Exception {
            document("one");

            list("?size=0").andExpect(jsonPath("$.content.length()").value(1));
        }

        @Test
        @DisplayName("a negative page is read as the first")
        void negativePageIsTheFirst() throws Exception {
            document("one");

            list("?page=-5").andExpect(status().isOk());
        }

        @Test
        @DisplayName("a page past the end is empty rather than an error")
        void pagePastTheEndIsEmpty() throws Exception {
            document("one");

            list("?page=99").andExpect(jsonPath("$.content.length()").value(0));
        }

        @Test
        @DisplayName("a project with no documents lists none rather than failing")
        void emptyProjectListsNothing() throws Exception {
            list("").andExpect(jsonPath("$.content.length()").value(0));
        }
    }

    @Nested
    @DisplayName("what each row says")
    class RowShape {

        @Test
        @DisplayName("names who uploaded it rather than exposing their record")
        void namesTheUploader() throws Exception {
            document("one");

            list("").andExpect(jsonPath("$.content[0].uploadedBy").value(USERNAME));
        }

        @Test
        @DisplayName("names the project it belongs to")
        void namesTheProject() throws Exception {
            document("one");

            list("").andExpect(jsonPath("$.content[0].projectId").value(projectId));
        }

        @Test
        @DisplayName("a document with no uploader recorded still lists")
        void survivesAnAbsentUploader() throws Exception {
            // A row whose uploader was deleted. Listing has to survive it,
            // because the alternative is a project nobody can open.
            documentRepo.save(Document.builder()
                .name("orphan").fileName("orphan.pdf").fileType("application/pdf")
                .filePath("/not/read/orphan.pdf").fileSize(1L)
                .documentType(Document.DocumentType.DRAWING)
                .project(projectRepo.findById(projectId).orElseThrow())
                .uploadedBy(null).build());

            list("").andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].uploadedBy").doesNotExist());
        }
    }
}
