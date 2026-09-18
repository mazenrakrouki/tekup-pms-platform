package com.pms.user.repository;

import com.pms.user.entity.TccAnnuel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface TccAnnuelRepository extends JpaRepository<TccAnnuel, Long> {

    @Query("SELECT t FROM TccAnnuel t WHERE t.resource.id = :resourceId AND t.deleted = false ORDER BY t.annee")
    List<TccAnnuel> findActiveByResourceId(Long resourceId);

    // r.deleted = false est indispensable : sans lui, le tarif annuel d'une ressource
    // archivée continuait d'alimenter le calcul de coût, alors que ResourceRepository,
    // lui, l'exclut. Une ressource recréée voyait donc son ancien tarif l'emporter.
    @Query("SELECT t FROM TccAnnuel t JOIN FETCH t.resource r JOIN FETCH r.user WHERE r.user.id IN :userIds AND r.deleted = false AND t.deleted = false")
    List<TccAnnuel> findActiveByUserIdIn(Collection<Long> userIds);
}
