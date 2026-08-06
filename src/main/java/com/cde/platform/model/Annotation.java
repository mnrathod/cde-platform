package com.cde.platform.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "annotations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Annotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Enumerated(EnumType.STRING)
    private AnnotationType type;

    // JSON shape data: {x, y, width, height, points[], radius, text, color, ...}
    @Column(name = "shape_data", columnDefinition = "TEXT", nullable = false)
    private String shapeData;

    private String comment;

    @Enumerated(EnumType.STRING)
    private AnnotationStatus status;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = AnnotationStatus.OPEN;
    }

    public enum AnnotationType {
        COMMENT, MARKUP, DIMENSION, CLOUD, ARROW, STAMP, HIGHLIGHT
    }

    public enum AnnotationStatus {
        OPEN, RESOLVED, CLOSED
    }
}
