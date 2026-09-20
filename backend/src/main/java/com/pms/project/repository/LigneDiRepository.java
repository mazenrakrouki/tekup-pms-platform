package com.pms.project.repository;

import com.pms.project.entity.LigneDi;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

// Database access for lignes_di, the lines of the Devis Interne (DI, the internal quote). No
// business rule or permission check lives here — those sit in DevisInterneService above it,
// which requires MANAGE_DI (given only to the Directeur role, per V23) plus the ADR-021 project-
// scope check on the controller's URL. Calling a method here from anywhere else would skip both.
public interface LigneDiRepository extends JpaRepository<LigneDi, Long> {

    // Every live (deleted = false) line of one project, ordered by section, then ordre, then id
    // for a stable read. The id tie-break matters because ordre defaults to 0, so several lines
    // can share it and would otherwise reshuffle between page loads. Partial index
    // idx_ligne_di_project (V23) covers both filters. Called by every DevisInterneService method.
    @Query("SELECT l FROM LigneDi l WHERE l.project.id = :projectId AND l.deleted = false ORDER BY l.section, l.ordre, l.id")
    List<LigneDi> findActiveByProjectId(Long projectId);

    // Whether a project has at least one live DI line, computed in the database (COUNT(l) > 0)
    // instead of loading every line just to check emptiness. No caller today; the one place
    // that asks this question, computeMargeVenduePct(), needs the lines anyway and tests the
    // list itself.
    @Query("SELECT COUNT(l) > 0 FROM LigneDi l WHERE l.project.id = :projectId AND l.deleted = false")
    boolean existsActiveByProjectId(Long projectId);
}
