package com.pms.billing.mapper;

import com.pms.billing.dto.PaiementResponse;
import com.pms.billing.entity.Paiement;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface PaiementMapper {

    @Mapping(target = "jalonId", source = "jalon.id")
    PaiementResponse toResponse(Paiement paiement);

    List<PaiementResponse> toResponseList(List<Paiement> paiements);
}
