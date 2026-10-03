-- ---------------------------------------------------------------------------
-- Let a revision that was never published be archived
--
-- V3 gave container_revisions this constraint:
--
--     CHECK (state NOT IN ('PUBLISHED', 'ARCHIVED')
--            OR (published_by IS NOT NULL AND published_at IS NOT NULL))
--
-- Its stated reason is publication: "a published revision must carry its
-- authorisation ... so no future code path can publish anonymously". Including
-- ARCHIVED went further than that reason, and further than the lifecycle
-- allows. Archived is the only terminal state, and not every revision reaches
-- it through publication — abandoned work in progress and a shared revision
-- that will not proceed both have to leave the active set, which
-- ContainerLifecycleService.archive() is written to do and documents as its
-- purpose. Neither was ever authorised, so neither has an approver to carry,
-- and the constraint refused the update: the call raised a constraint
-- violation, which surfaces as a 500 rather than as anything the caller could
-- act on.
--
-- Filling the field in to satisfy the constraint would be worse than leaving
-- it: it would record a named person as having authorised information that
-- nobody authorised, in the column a contractual dispute reads first.
--
-- So the constraint is narrowed to the case its comment describes. The
-- guarantee it was protecting is unaffected, because it was never the only
-- thing protecting it: trg_published_revisions_are_immutable fires on every
-- UPDATE of a row whose state is PUBLISHED or ARCHIVED and refuses any change
-- to published_by, published_at or approval_reason. A published revision
-- therefore still cannot lose its approver on the way to being archived — that
-- path is closed by the trigger, not by this CHECK.
--
-- Relaxing a CHECK is backwards-compatible: every row that satisfied the old
-- constraint satisfies the new one, so this deploys against a running fleet
-- (§7.5).
-- ---------------------------------------------------------------------------

ALTER TABLE container_revisions
    DROP CONSTRAINT revisions_published_has_approver;

ALTER TABLE container_revisions
    ADD CONSTRAINT revisions_published_has_approver
        CHECK (state <> 'PUBLISHED'
               OR (published_by IS NOT NULL AND published_at IS NOT NULL));
