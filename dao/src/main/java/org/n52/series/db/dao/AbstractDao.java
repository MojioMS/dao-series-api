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

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.criterion.Conjunction;
import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.LogicalExpression;
import org.hibernate.criterion.ProjectionList;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.hibernate.criterion.Subqueries;
import org.hibernate.engine.spi.LoadQueryInfluencers;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.internal.CriteriaImpl;
import org.hibernate.loader.criteria.CriteriaJoinWalker;
import org.hibernate.loader.criteria.CriteriaQueryTranslator;
import org.hibernate.persister.entity.OuterJoinLoadable;
import org.hibernate.spatial.GeometryType;
import org.hibernate.spatial.criterion.SpatialRestrictions;
import org.n52.io.request.FilterResolver;
import org.n52.io.request.IoParameters;
import org.n52.series.db.DataAccessException;
import org.n52.series.db.beans.DataEntity;
import org.n52.series.db.beans.DatasetEntity;
import org.n52.series.db.beans.DescribableEntity;
import org.n52.series.db.beans.I18nEntity;
import org.n52.series.db.beans.PlatformEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractDao<T> implements GenericDao<T, Long> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractDao.class);

    protected final Session session;

    public AbstractDao(Session session) {
        if (session == null) {
            throw new NullPointerException("Cannot operate on a null session.");
        }
        this.session = session;
    }

    protected abstract Class<T> getEntityClass();

    protected abstract String getDatasetProperty();

    protected String getDefaultAlias() {
        return getDatasetProperty();
    }

    public boolean hasInstance(String id, DbQuery query) throws DataAccessException {
        return getInstance(id, query) != null;
    }

    public boolean hasInstance(String id, DbQuery query, Class< ? > clazz) throws DataAccessException {
        return getInstance(id, query) != null;
    }

    @Override
    public boolean hasInstance(Long id, DbQuery query) {
        return session.get(getEntityClass(), id) != null;
    }

    public boolean hasInstance(Long id, DbQuery query, Class< ? > clazz) {
        return session.get(clazz, id) != null;
    }

    public T getInstance(String key, DbQuery query) throws DataAccessException {
        return getInstance(key, query, getEntityClass());
    }

    @Override
    public T getInstance(Long key, DbQuery query) throws DataAccessException {
        LOGGER.debug("get instance '{}': {}", key, query);
        return getInstance(Long.toString(key), query, getEntityClass());
    }

    protected T getInstance(String key, DbQuery query, Class<T> clazz) {
        LOGGER.debug("get instance for '{}'. {}", key, query);
        Criteria criteria = getDefaultCriteria(query, clazz);
        return getInstance(key, query, clazz, criteria);
    }

    protected T getInstance(String key, DbQuery query, Class<T> clazz, Criteria criteria) {
        Criteria instanceCriteria = query.isMatchDomainIds()
                ? criteria.add(Restrictions.eq(DescribableEntity.PROPERTY_DOMAIN_ID, key))
                : criteria.add(Restrictions.eq(DescribableEntity.PROPERTY_PKID, Long.parseLong(key)));
        return clazz.cast(instanceCriteria.uniqueResult());
    }

    @Override
    public Integer getCount(DbQuery query) throws DataAccessException {
        Criteria criteria = getDefaultCriteria(query).setProjection(Projections.rowCount());
        return ((Long) criteria.uniqueResult()).intValue();
    }

    protected <I extends I18nEntity> Criteria i18n(Class<I> clazz, Criteria criteria, DbQuery query) {
        return hasTranslation(query, clazz)
                ? query.addLocaleTo(criteria, clazz)
                : criteria;
    }

    private <I extends I18nEntity> boolean hasTranslation(DbQuery parameters, Class<I> clazz) {
        Criteria i18nCriteria = session.createCriteria(clazz);
        return parameters.checkTranslationForLocale(i18nCriteria);
    }

    public Criteria getDefaultCriteria(DbQuery query) {
        return getDefaultCriteria((String) null, query);
    }

    public Criteria getDefaultCriteria(String alias, DbQuery query) {
        return getDefaultCriteria(alias, query, getEntityClass());
    }

    private Criteria getDefaultCriteria(DbQuery query, Class< ? > clazz) {
        return getDefaultCriteria((String) null, query, clazz);
    }

    protected Criteria getDefaultCriteria(String alias, DbQuery query, Class< ? > clazz) {
        String nonNullAlias = alias != null
                ? alias
                : getDefaultAlias();
        Criteria criteria = session.createCriteria(clazz, nonNullAlias);
        criteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
        addDatasetFilters(query, criteria);
        addPlatformTypeFilter(getDatasetProperty(), criteria, query);
        addValueTypeFilter(getDatasetProperty(), criteria, query);
        addGeometryTypeFilter(query, criteria);
        return criteria;
    }

    protected Criteria addDatasetFilters(DbQuery query, Criteria criteria) {
        DetachedCriteria filter = createDatasetSubqueryViaExplicitJoin(query);
        return criteria.add(Subqueries.propertyIn("pkid", filter));
    }

    private DetachedCriteria createDatasetSubqueryViaExplicitJoin(DbQuery query) {
        DetachedCriteria subquery = DetachedCriteria.forClass(DatasetEntity.class)
                                                    .add(createPublishedDatasetFilter());
        if (getDatasetProperty().equalsIgnoreCase(DatasetEntity.PROPERTY_FEATURE)) {
            DetachedCriteria featureCriteria = addSpatialFilter(query, subquery);
            return featureCriteria.setProjection(Projections.property(DescribableEntity.PROPERTY_PKID));
        } else {
            addSpatialFilter(query, subquery);
            return subquery.createCriteria(getDatasetProperty())
                           .setProjection(Projections.property(DescribableEntity.PROPERTY_PKID));

        }
    }

    protected final Conjunction createPublishedDatasetFilter() {
        return Restrictions.and(Restrictions.eq(DatasetEntity.PROPERTY_PUBLISHED, true),
                                Restrictions.eq(DatasetEntity.PROPERTY_DELETED, false),
                                Restrictions.isNotNull(DatasetEntity.PROPERTY_FIRST_VALUE_AT),
                                Restrictions.isNotNull(DatasetEntity.PROPERTY_LAST_VALUE_AT));
    }

    /**
     * @param query
     *        the query instance
     * @param criteria
     *        the current detached criteria
     * @return the detached criteria for chaining
     */
    protected DetachedCriteria addSpatialFilter(DbQuery query, DetachedCriteria criteria) {
        return query.addSpatialFilter(criteria.createCriteria(DatasetEntity.PROPERTY_FEATURE));
    }

    protected Criteria addValueTypeFilter(String parameter, Criteria criteria, DbQuery query) {
        IoParameters parameters = query.getParameters();
        Set<String> valueTypes = parameters.getValueTypes();
        if (!valueTypes.isEmpty()) {
            FilterResolver filterResolver = parameters.getFilterResolver();
            if (parameters.shallBehaveBackwardsCompatible() || !filterResolver.shallIncludeAllDatasetTypes()) {
                Criterion containsValueType = Restrictions.in(DatasetEntity.PROPERTY_VALUE_TYPE, valueTypes);
                if (parameter == null || parameter.isEmpty()) {
                    // series table itself
                    criteria.add(containsValueType);
                } else {
                    ProjectionList onPkids = matchPropertyPkids(DatasetEntity.ENTITY_ALIAS, parameter);
                    DetachedCriteria c = DetachedCriteria.forClass(DatasetEntity.class, DatasetEntity.ENTITY_ALIAS)
                                                         .add(containsValueType)
                                                         .setProjection(onPkids);
                    criteria.add(matchPropertyPkids(parameter, c));
                }
            }
        }
        return criteria;
    }

    protected Criteria addPlatformTypeFilter(String parameter, Criteria criteria, DbQuery query) {
        IoParameters parameters = query.getParameters();
        FilterResolver filterResolver = parameters.getFilterResolver();
        if (!filterResolver.shallIncludeAllPlatformTypes()) {
            if (parameter == null || parameter.isEmpty()) {
                // join starts from dataset table
                criteria.createCriteria(DatasetEntity.PROPERTY_PLATFORM)
                        .add(createMobileExpression(filterResolver))
                        .add(createInsituExpression(filterResolver));
            } else {
                DetachedCriteria c = DetachedCriteria.forClass(DatasetEntity.class, DatasetEntity.ENTITY_ALIAS)
                                                     .createCriteria(DatasetEntity.PROPERTY_PROCEDURE)
                                                     .add(createMobileExpression(filterResolver))
                                                     .add(createInsituExpression(filterResolver))
                                                     .setProjection(matchPropertyPkids(DatasetEntity.ENTITY_ALIAS,
                                                                                       parameter));
                criteria.add(matchPropertyPkids(parameter, c));
            }
        }
        return criteria;
    }

    private LogicalExpression createMobileExpression(FilterResolver filterResolver) {
        boolean includeStationary = filterResolver.shallIncludeStationaryPlatformTypes();
        boolean includeMobile = filterResolver.shallIncludeMobilePlatformTypes();
        return Restrictions.or(Restrictions.eq(PlatformEntity.PROPERTY_MOBILE, !includeStationary),
                               Restrictions.eq(PlatformEntity.PROPERTY_MOBILE, includeMobile));
    }

    private LogicalExpression createInsituExpression(FilterResolver filterResolver) {
        boolean includeInsitu = filterResolver.shallIncludeInsituPlatformTypes();
        boolean includeRemote = filterResolver.shallIncludeRemotePlatformTypes();
        return Restrictions.or(Restrictions.eq(PlatformEntity.PROPERTY_INSITU, includeInsitu),
                               Restrictions.eq(PlatformEntity.PROPERTY_INSITU, !includeRemote));
    }

    private ProjectionList matchPropertyPkids(String alias, String property) {
        String member = QueryUtils.createAssociation(alias, property);
        String association = QueryUtils.createAssociation(member, DescribableEntity.PROPERTY_PKID);
        return Projections.projectionList()
                          .add(Projections.property(association));
    }

    private Criterion matchPropertyPkids(String property, DetachedCriteria c) {
        return Subqueries.propertyIn(QueryUtils.createAssociation(property, DescribableEntity.PROPERTY_PKID), c);
    }

    protected Criteria addGeometryTypeFilter(DbQuery query, Criteria criteria) {
        query.getParameters()
                .getGeometryTypes()
                .stream()
                .filter(geometryType -> !geometryType.isEmpty())
                .map(this::getGeometryType)
                .filter(Objects::nonNull)
                .map(type -> SpatialRestrictions.geometryType(DataEntity.PROPERTY_GEOMETRY_ENTITY, type))
                .forEach(criteria::add);
        return criteria;
    }

    private GeometryType.Type getGeometryType(String geometryType) {
        return Arrays.stream(GeometryType.Type.values())
                .filter(type -> type.name().equalsIgnoreCase(geometryType))
                .findAny().orElse(null);
    }

    /**
     * Translate the {@link Criteria criteria} to SQL.
     *
     * @param criteria the criteria
     *
     * @return the SQL
     */
    public static String toSQLString(Criteria criteria) {
        if (!(criteria instanceof CriteriaImpl)) {
            return criteria.toString();
        }
        CriteriaImpl criteriaImpl = (CriteriaImpl) criteria;
        SessionImplementor session = criteriaImpl.getSession();
        SessionFactoryImplementor factory = session.getFactory();
        String entityOrClassName = criteriaImpl.getEntityOrClassName();
        CriteriaQueryTranslator translator = new CriteriaQueryTranslator(factory, criteriaImpl, entityOrClassName,
                                                                         CriteriaQueryTranslator.ROOT_SQL_ALIAS);
        String[] implementors = factory.getImplementors(entityOrClassName);
        OuterJoinLoadable outerJoinLoadable = (OuterJoinLoadable) factory.getEntityPersister(implementors[0]);
        LoadQueryInfluencers loadQueryInfluencers = session.getLoadQueryInfluencers();
        CriteriaJoinWalker walker = new CriteriaJoinWalker(outerJoinLoadable, translator, factory, criteriaImpl,
                                                           entityOrClassName, loadQueryInfluencers);
        return walker.getSQLString();
    }
}
