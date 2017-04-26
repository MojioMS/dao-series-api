/*
 * Copyright (C) 2015-2017 52°North Initiative for Geospatial Open Source
 * Software GmbH
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 as published
 * by the Free Software Foundation.
 *
 * If the program is linked with libraries which are licensed under one of
 * the following licenses, the combination of the program with the linked
 * library is not considered a "derivative work" of the program:
 *
 *     - Apache License, version 2.0
 *     - Apache Software License, version 1.0
 *     - GNU Lesser General Public License, version 3
 *     - Mozilla Public License, versions 1.0, 1.1 and 2.0
 *     - Common Development and Distribution License (CDDL), version 1.0
 *
 * Therefore the distribution of the program linked with libraries licensed
 * under the aforementioned licenses, is permitted by the copyright holders
 * if the distribution is compliant with both the GNU General Public License
 * version 2 and the aforementioned licenses.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 */

package org.n52.series.db.dao;

import java.util.List;

import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.criterion.Restrictions;
import org.n52.series.db.DataAccessException;
import org.n52.series.db.beans.DatasetEntity;
import org.n52.series.db.beans.I18nProcedureEntity;
import org.n52.series.db.beans.ProcedureEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class ProcedureDao extends AbstractDao<ProcedureEntity> {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcedureDao.class);

    private static final String COLUMN_REFERENCE = "reference";

    public ProcedureDao(Session session) {
        super(session);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ProcedureEntity> find(DbQuery query) {
        Criteria criteria = i18n(I18nProcedureEntity.class, getDefaultCriteria(true), query);
        criteria.add(Restrictions.ilike(ProcedureEntity.PROPERTY_NAME, "%" + query.getSearchTerm() + "%"));
        return query.addFilters(criteria, getDatasetProperty())
                    .list();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ProcedureEntity> getAllInstances(DbQuery query) throws DataAccessException {
        Criteria criteria = i18n(I18nProcedureEntity.class, getDefaultCriteria(true), query);
        return (List<ProcedureEntity>) query.addFilters(criteria, getDatasetProperty())
                                            .list();
    }

    @Override
    public ProcedureEntity getInstance(Long key, DbQuery parameters) throws DataAccessException {
        LOGGER.debug("get instance '{}': {}", key, parameters);
        Criteria criteria = getDefaultCriteria(true);
        return getEntityClass().cast(criteria.add(Restrictions.eq("pkid", key))
                                             .uniqueResult());
    }

    @Override
    protected Criteria getDefaultCriteria() {
        return getDefaultCriteria(true);
    }

    private Criteria getDefaultCriteria(boolean ignoreReferenceProcedures) {
        return ignoreReferenceProcedures
                ? super.getDefaultCriteria().add(Restrictions.eq(COLUMN_REFERENCE, Boolean.FALSE))
                : super.getDefaultCriteria();
    }

    @Override
    protected String getDatasetProperty() {
        return DatasetEntity.PROPERTY_PROCEDURE;
    }

    @Override
    protected Class<ProcedureEntity> getEntityClass() {
        return ProcedureEntity.class;
    }

}
