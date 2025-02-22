/*
 * Copyright 2017 IntroPro Ventures Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.introproventures.graphql.jpa.query.schema.impl;

import static com.introproventures.graphql.jpa.query.schema.impl.GraphQLJpaSchemaBuilder.SELECT_DISTINCT_PARAM_NAME;
import static com.introproventures.graphql.jpa.query.support.GraphQLSupport.isAfterArgument;
import static com.introproventures.graphql.jpa.query.support.GraphQLSupport.isDistinctArgument;
import static com.introproventures.graphql.jpa.query.support.GraphQLSupport.isFirstArgument;
import static com.introproventures.graphql.jpa.query.support.GraphQLSupport.isLogicalArgument;
import static com.introproventures.graphql.jpa.query.support.GraphQLSupport.isPageArgument;
import static com.introproventures.graphql.jpa.query.support.GraphQLSupport.isWhereArgument;
import static com.introproventures.graphql.jpa.query.support.GraphQLSupport.selections;
import static graphql.introspection.Introspection.SchemaMetaFieldDef;
import static graphql.introspection.Introspection.TypeMetaFieldDef;
import static graphql.introspection.Introspection.TypeNameMetaFieldDef;
import static java.util.stream.Collectors.groupingBy;

import com.introproventures.graphql.jpa.query.annotation.GraphQLDefaultOrderBy;
import com.introproventures.graphql.jpa.query.introspection.ReflectionUtil;
import com.introproventures.graphql.jpa.query.schema.JavaScalars;
import com.introproventures.graphql.jpa.query.schema.RestrictedKeysProvider;
import com.introproventures.graphql.jpa.query.schema.impl.EntityIntrospector.EntityIntrospectionResult;
import com.introproventures.graphql.jpa.query.schema.impl.EntityIntrospector.EntityIntrospectionResult.AttributePropertyDescriptor;
import com.introproventures.graphql.jpa.query.schema.impl.PredicateFilter.Criteria;
import com.introproventures.graphql.jpa.query.support.GraphQLSupport;
import graphql.GraphQLException;
import graphql.execution.CoercedVariables;
import graphql.execution.MergedField;
import graphql.execution.ValuesResolver;
import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.Field;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.language.VariableReference;
import graphql.schema.Coercing;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Subgraph;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Attribute.PersistentAttributeType;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides implemetation for GraphQL JPA Query Factory
 *
 * @author Igor Dianov
 *
 */
public final class GraphQLJpaQueryFactory {

    private static final String DESC = "DESC";

    private static final Logger logger = LoggerFactory.getLogger(GraphQLJpaQueryFactory.class);
    public static final String JAKARTA_PERSISTENCE_FETCHGRAPH = "jakarta.persistence.fetchgraph";
    private static Function<Object, Object> unproxy;

    static {
        try {
            Class<?> hibernateClass = Class.forName("org.hibernate.Hibernate");
            Method unproxyMethod = hibernateClass.getDeclaredMethod("unproxy", Object.class);

            unproxy =
                proxy -> {
                    try {
                        return unproxyMethod.invoke(null, proxy);
                    } catch (Exception ignored) {}

                    return proxy;
                };
        } catch (Exception ignored) {
            unproxy = Function.identity();
        }
    }

    protected static final String WHERE = "where";
    protected static final String OPTIONAL = "optional";

    protected static final String ORG_HIBERNATE_CACHEABLE = "org.hibernate.cacheable";
    protected static final String ORG_HIBERNATE_FETCH_SIZE = "org.hibernate.fetchSize";
    protected static final String ORG_HIBERNATE_READ_ONLY = "org.hibernate.readOnly";

    private final Map<GraphQLObjectType, EntityType> entityTypeMap = new ConcurrentHashMap<>();
    private final Map<GraphQLObjectType, EmbeddableType> embeddableTypeMap = new ConcurrentHashMap<>();

    protected final EntityManager entityManager;
    protected final EntityType<?> entityType;
    private final boolean toManyDefaultOptional;
    private final boolean defaultDistinct;
    private final String selectNodeName;
    private final GraphQLObjectType entityObjectType;
    private final int defaultFetchSize;
    private final RestrictedKeysProvider restrictedKeysProvider;
    private final boolean resultStream;
    private final GraphQLObjectTypeMetadata graphQLObjectTypeMetadata;

    private GraphQLJpaQueryFactory(Builder builder) {
        this.entityManager = builder.entityManager;
        this.entityType = builder.entityType;
        this.entityObjectType = builder.entityObjectType;
        this.selectNodeName = builder.selectNodeName;
        this.toManyDefaultOptional = builder.toManyDefaultOptional;
        this.defaultDistinct = builder.defaultDistinct;
        this.defaultFetchSize = builder.defaultFetchSize;
        this.restrictedKeysProvider = builder.restrictedKeysProvider;
        this.resultStream = builder.resultStream;
        this.graphQLObjectTypeMetadata = builder.graphQLObjectTypeMetadata;
    }

    public DataFetchingEnvironment getQueryEnvironment(DataFetchingEnvironment environment, MergedField queryField) {
        // Override query environment with associated entity object type and select field
        return DataFetchingEnvironmentBuilder
            .newDataFetchingEnvironment(environment)
            .fieldType(getEntityObjectType())
            .mergedField(queryField)
            .build();
    }

    public Optional<List<Object>> getRestrictedKeys(DataFetchingEnvironment environment) {
        EntityIntrospectionResult entityDescriptor = EntityIntrospector.introspect(entityType);

        Optional<List<Object>> restrictedKeys = restrictedKeysProvider.apply(entityDescriptor);
        List<Object> restrictedKeysValues = new ArrayList<>();

        if (restrictedKeys.isPresent()) {
            restrictedKeys
                .get()
                .stream()
                .filter(key -> !"*".equals(key))
                .map(key -> {
                    Type<?> idType = entityType.getIdType();
                    Class<?> clazz = idType.getJavaType();
                    GraphQLScalarType scalar = JavaScalars.of(clazz);

                    return scalar.getCoercing().parseValue(key);
                })
                .forEach(restrictedKeysValues::add);

            return Optional.of(restrictedKeysValues);
        }

        return Optional.empty();
    }

    public List<Object> queryKeys(
        DataFetchingEnvironment environment,
        int firstResult,
        int maxResults,
        List<Object> restrictedKeys
    ) {
        MergedField queryField = resolveQueryField(environment.getField());

        // Override query environment with associated entity object type and
        final DataFetchingEnvironment queryEnvironment = getQueryEnvironment(environment, queryField);
        TypedQuery<Object> keysQuery = getKeysQuery(queryEnvironment, queryEnvironment.getField(), restrictedKeys);

        keysQuery.setFirstResult(firstResult).setMaxResults(maxResults);

        if (logger.isDebugEnabled()) {
            logger.info("\nGraphQL JPQL Keys Query String:\n    {}", getJPQLQueryString(keysQuery));
        }

        if (logger.isTraceEnabled()) {
            logger.trace(
                "Query keys in session {} for field {} on {}",
                entityManager,
                environment.getField().getName(),
                Thread.currentThread()
            );
        }

        return keysQuery.getResultList();
    }

    public List<Object> queryResultList(DataFetchingEnvironment environment, int maxResults, List<Object> keys) {
        // Let's execute query and get result as stream
        Stream<Object> resultStream = queryResultStream(environment, maxResults, keys);
        // Let's wrap stream into lazy list to pass it downstream
        return ResultStreamWrapper.wrap(resultStream, maxResults);
    }

    protected Stream<Object> queryResultStream(DataFetchingEnvironment environment, int maxResults, List<Object> keys) {
        MergedField queryField = resolveQueryField(environment.getField());

        // Override query environment with associated entity object type and
        final DataFetchingEnvironment queryEnvironment = getQueryEnvironment(environment, queryField);
        final int fetchSize = Integer.min(maxResults, defaultFetchSize);
        final boolean isDistinct = resolveDistinctArgument(queryEnvironment.getField());

        final TypedQuery<Object> query = getQuery(
            queryEnvironment,
            queryEnvironment.getField(),
            isDistinct,
            keys.toArray()
        );

        // Let's create entity graph from selection
        var entityGraph = createEntityGraph(queryEnvironment);

        // Let's execute query and get wrap result into stream
        return getResultStream(query, fetchSize, isDistinct, entityGraph);
    }

    protected <T> Stream<T> getResultStream(
        TypedQuery<T> query,
        int fetchSize,
        boolean isDistinct,
        EntityGraph<?> entityGraph
    ) {
        // Let' try reduce overhead and disable all caching
        query.setHint(ORG_HIBERNATE_READ_ONLY, true);
        query.setHint(ORG_HIBERNATE_FETCH_SIZE, fetchSize);
        query.setHint(ORG_HIBERNATE_CACHEABLE, false);
        if (entityGraph != null) {
            query.setHint(JAKARTA_PERSISTENCE_FETCHGRAPH, entityGraph);
        }

        if (logger.isDebugEnabled()) {
            logger.info("\nGraphQL JPQL Fetch Query String:\n    {}", getJPQLQueryString(query));
        }
        if (logger.isTraceEnabled()) {
            logger.trace(
                "Query results in session {} for query {} running on {}",
                entityManager,
                getJPQLQueryString(query),
                Thread.currentThread()
            );
        }

        // Let's execute query and wrap result into stream
        final Stream<T> resultStream = this.resultStream ? query.getResultStream() : query.getResultList().stream();

        return resultStream.map(this::unproxy).peek(this::detach);
    }

    protected Object querySingleResult(final DataFetchingEnvironment environment) {
        final MergedField queryField = flattenEmbeddedIdArguments(environment.getField());

        final DataFetchingEnvironment queryEnvironment = getQueryEnvironment(environment, queryField);

        Optional<List<Object>> restrictedKeys = getRestrictedKeys(queryEnvironment);

        if (restrictedKeys.isPresent()) {
            TypedQuery<Object> query = getQuery(
                queryEnvironment,
                queryEnvironment.getField(),
                true,
                restrictedKeys.get().toArray()
            );

            if (logger.isDebugEnabled()) {
                logger.info("\nGraphQL JPQL Single Result Query String:\n    {}", getJPQLQueryString(query));
            }

            return Optional.ofNullable(query.getSingleResult()).map(this::unproxyAndThenDetach).orElse(null);
        }

        return null;
    }

    public Long queryTotalCount(DataFetchingEnvironment environment, Optional<List<Object>> restrictedKeys) {
        final MergedField queryField = flattenEmbeddedIdArguments(environment.getField());

        final DataFetchingEnvironment queryEnvironment = getQueryEnvironment(environment, queryField);

        if (restrictedKeys.isPresent()) {
            TypedQuery<Long> countQuery = getCountQuery(
                queryEnvironment,
                queryEnvironment.getField(),
                restrictedKeys.get()
            );

            if (logger.isDebugEnabled()) {
                logger.info("\nGraphQL JPQL Count Query String:\n    {}", getJPQLQueryString(countQuery));
            }

            return countQuery.getSingleResult();
        }

        return 0L;
    }

    protected <T> TypedQuery<T> getQuery(
        DataFetchingEnvironment environment,
        Field field,
        boolean isDistinct,
        Object... keys
    ) {
        DataFetchingEnvironment queryEnvironment = DataFetchingEnvironmentBuilder
            .newDataFetchingEnvironment(environment)
            .localContext(Boolean.TRUE) // Fetch mode
            .build();

        CriteriaQuery<T> criteriaQuery = getCriteriaQuery(queryEnvironment, field, isDistinct, keys);

        return entityManager.createQuery(criteriaQuery);
    }

    protected TypedQuery<Long> getCountQuery(DataFetchingEnvironment environment, Field field, List<Object> keys) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<?> root = query.from(entityType);

        DataFetchingEnvironment queryEnvironment = DataFetchingEnvironmentBuilder
            .newDataFetchingEnvironment(environment)
            .root(query)
            .localContext(Boolean.FALSE) // Join mode
            .build();
        root.alias("root");

        query.select(cb.count(root));

        List<Predicate> predicates = field
            .getArguments()
            .stream()
            .map(it -> getPredicate(field, cb, root, null, queryEnvironment, it))
            .filter(it -> it != null)
            .collect(Collectors.toList());

        if (!keys.isEmpty() && hasIdAttribute()) {
            Predicate restrictions = root.get(idAttributeName()).in(keys);
            predicates.add(restrictions);
        }

        query.where(predicates.toArray(new Predicate[0]));

        return entityManager.createQuery(query);
    }

    protected TypedQuery<Object> getKeysQuery(DataFetchingEnvironment environment, Field field, List<Object> keys) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object> query = cb.createQuery(Object.class);
        Root<?> from = query.from(entityType);

        from.alias("root");

        DataFetchingEnvironment queryEnvironment = DataFetchingEnvironmentBuilder
            .newDataFetchingEnvironment(environment)
            .root(query)
            .localContext(Boolean.FALSE)
            .build();
        if (hasIdAttribute()) {
            query.select(from.get(idAttributeName()));
        } else if (hasIdClassAttribue()) {
            List<Selection<?>> selection = Stream
                .of(idClassAttributeNames())
                .map(from::get)
                .collect(Collectors.toList());
            query.multiselect(selection);
        }

        List<Predicate> predicates = field
            .getArguments()
            .stream()
            .map(it -> getPredicate(field, cb, from, null, queryEnvironment, it))
            .filter(it -> it != null)
            .collect(Collectors.toList());

        if (!keys.isEmpty() && hasIdAttribute()) {
            Predicate restrictions = from.get(idAttributeName()).in(keys);
            predicates.add(restrictions);
        }

        query.where(predicates.toArray(new Predicate[0]));

        GraphQLSupport
            .fields(field.getSelectionSet())
            .filter(it -> isPersistent(environment, it.getName()))
            .forEach(selection -> {
                Path<?> selectionPath = from.get(selection.getName());

                // Process the orderBy clause
                mayBeAddOrderBy(selection, query, cb, selectionPath, queryEnvironment);
            });

        mayBeAddDefaultOrderBy(query, from, cb);

        return entityManager.createQuery(query);
    }

    protected Map<Object, List<Object>> loadOneToMany(DataFetchingEnvironment environment, Set<Object> keys) {
        Field field = environment.getField();

        TypedQuery<Object[]> query = getBatchQuery(environment, field, isDefaultDistinct(), keys);

        var entityGraph = createEntityGraph(environment);

        List<Object[]> resultList = getResultList(query, entityGraph);

        if (logger.isTraceEnabled()) {
            logger.trace(
                "loadOneToMany in session {} for field {} with keys {} on {}",
                entityManager,
                environment.getField().getName(),
                keys,
                Thread.currentThread()
            );
        }

        Map<Object, List<Object>> batch = resultList
            .stream()
            .collect(
                groupingBy(
                    t -> t[0],
                    Collectors.mapping(t -> this.unproxyAndThenDetach(t[1]), GraphQLSupport.toResultList())
                )
            );
        Map<Object, List<Object>> resultMap = new LinkedHashMap<>(keys.size());

        keys.forEach(it -> {
            List<Object> list = batch.getOrDefault(it, Collections.emptyList());

            resultMap.put(it, list);
        });

        return resultMap;
    }

    protected Map<Object, Object> loadManyToOne(DataFetchingEnvironment environment, Set<Object> keys) {
        Field field = environment.getField();

        TypedQuery<Object[]> query = getBatchQuery(environment, field, isDefaultDistinct(), keys);

        var entityGraph = createEntityGraph(environment);

        List<Object[]> resultList = getResultList(query, entityGraph);

        Map<Object, Object> resultMap = new LinkedHashMap<>(resultList.size());

        resultList.forEach(item -> resultMap.put(item[0], this.unproxyAndThenDetach(item[1])));

        return resultMap;
    }

    protected <T> List<T> getResultList(TypedQuery<T> query, EntityGraph<?> entityGraph) {
        if (logger.isDebugEnabled()) {
            logger.info("\nGraphQL JPQL Batch Query String:\n    {}", getJPQLQueryString(query));
        }

        // Let' try reduce overhead and disable all caching
        query.setHint(ORG_HIBERNATE_READ_ONLY, true);
        query.setHint(ORG_HIBERNATE_FETCH_SIZE, defaultFetchSize);
        query.setHint(ORG_HIBERNATE_CACHEABLE, false);

        if (entityGraph != null) {
            query.setHint(JAKARTA_PERSISTENCE_FETCHGRAPH, entityGraph);
        }

        return query.getResultList();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected TypedQuery<Object[]> getBatchQuery(
        DataFetchingEnvironment environment,
        Field field,
        boolean isDistinct,
        Set<Object> keys
    ) {
        SingularAttribute parentIdAttribute = entityType.getId(Object.class);

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        //CriteriaQuery<Object> query = cb.createQuery((Class<Object>) entityType.getJavaType());
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<?> from = query.from(entityType);

        DataFetchingEnvironment queryEnvironment = DataFetchingEnvironmentBuilder
            .newDataFetchingEnvironment(environment)
            .root(query)
            .localContext(Boolean.TRUE)
            .build();

        from.alias("owner");

        // Must use inner join in parent context
        Join join = from.join(field.getName()).on(from.get(parentIdAttribute.getName()).in(keys));

        query.multiselect(from.get(parentIdAttribute.getName()), join.alias(field.getName()));

        List<Predicate> predicates = getFieldPredicates(field, query, cb, from, join, queryEnvironment);

        query.where(predicates.toArray(new Predicate[0]));

        // optionally add default ordering
        // FIXME need to extract correct id attribute from collection entity
        // mayBeAddDefaultOrderBy(query, join, cb);

        return entityManager.createQuery(query.distinct(isDistinct));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    protected TypedQuery<Object> getBatchCollectionQuery(
        DataFetchingEnvironment environment,
        Field field,
        boolean isDistinct,
        Set<Object> keys
    ) {
        SingularAttribute parentIdAttribute = entityType.getId(Object.class);

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        //CriteriaQuery<Object> query = cb.createQuery((Class<Object>) entityType.getJavaType());
        CriteriaQuery<Object> query = cb.createQuery();
        Root<?> from = query.from(entityType);

        DataFetchingEnvironment queryEnvironment = DataFetchingEnvironmentBuilder
            .newDataFetchingEnvironment(environment)
            .root(query)
            .localContext(Boolean.TRUE)
            .build();

        from.alias("owner");

        // Must use inner join in parent context
        Join join = from.join(field.getName()).on(from.get(parentIdAttribute.getName()).in(keys));

        query.select(join.alias(field.getName()));

        List<Predicate> predicates = getFieldPredicates(field, query, cb, from, join, queryEnvironment);

        query.where(predicates.toArray(new Predicate[0]));

        // optionally add default ordering
        mayBeAddDefaultOrderBy(query, join, cb);

        return entityManager.createQuery(query.distinct(isDistinct));
    }

    @SuppressWarnings("unchecked")
    protected <T> CriteriaQuery<T> getCriteriaQuery(
        DataFetchingEnvironment environment,
        Field field,
        boolean isDistinct,
        Object... keys
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<T> query = cb.createQuery((Class<T>) entityType.getJavaType());
        Root<?> from = query.from(entityType);

        DataFetchingEnvironment queryEnvironment = DataFetchingEnvironmentBuilder
            .newDataFetchingEnvironment(environment)
            .root(query)
            .build();
        from.alias(from.getModel().getName().toLowerCase());

        // Build predicates from query arguments
        List<Predicate> predicates = getFieldPredicates(field, query, cb, from, from, queryEnvironment);

        if (keys.length > 0) {
            if (hasIdAttribute()) {
                predicates.add(from.get(idAttributeName()).in(keys));
            } // array of idClass attributes
            else if (hasIdClassAttribue()) {
                String[] names = idClassAttributeNames();
                Map<String, List<Object>> idKeys = new HashMap<>();

                IntStream
                    .range(0, keys.length)
                    .mapToObj(i -> (Object[]) keys[i])
                    .forEach(values -> {
                        IntStream
                            .range(0, values.length)
                            .forEach(i -> {
                                idKeys.computeIfAbsent(names[i], key -> new ArrayList<>()).add(values[i]);
                            });
                    });

                List<Predicate> idPredicates = Stream
                    .of(names)
                    .map(name -> {
                        return from.get(name).in(idKeys.get(name).toArray(new Object[0]));
                    })
                    .collect(Collectors.toList());

                predicates.add(cb.and(idPredicates.toArray(new Predicate[0])));
            }
        }

        // Use AND clause to filter results
        if (!predicates.isEmpty()) query.where(predicates.toArray(new Predicate[0]));

        // optionally add default ordering
        mayBeAddDefaultOrderBy(query, from, cb);

        return query.distinct(isDistinct);
    }

    protected void mayBeAddOrderBy(
        Field selectedField,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Path<?> fieldPath,
        DataFetchingEnvironment environment
    ) {
        Attribute<?, ?> attribute = getAttribute(environment, selectedField.getName());
        // Singular attributes only
        if (attribute instanceof SingularAttribute) {
            selectedField
                .getArguments()
                .stream()
                .filter(this::isOrderByArgument)
                .findFirst()
                .map(argument -> getOrderByValue(argument, environment))
                .ifPresent(orderBy -> {
                    List<Order> orders = new ArrayList<>(query.getOrderList());

                    if (DESC.equals(orderBy.getName())) {
                        orders.add(cb.desc(fieldPath));
                    } else {
                        orders.add(cb.asc(fieldPath));
                    }

                    query.orderBy(orders);
                });
        }
    }

    protected List<Predicate> getFieldPredicates(
        Field field,
        CriteriaQuery<?> query,
        CriteriaBuilder cb,
        Root<?> root,
        From<?, ?> from,
        DataFetchingEnvironment environment
    ) {
        List<Argument> arguments = new ArrayList<>();
        List<Predicate> predicates = new ArrayList<>();

        // Loop through all of the fields being requested
        GraphQLSupport
            .fields(field.getSelectionSet())
            .filter(selection -> isPersistent(environment, selection.getName()))
            .forEach(selection -> {
                Path<?> fieldPath = from.get(selection.getName());
                From<?, ?> fetch = null;
                final Optional<Argument> whereArgument = getArgument(selection, WHERE);
                final Attribute<?, ?> fieldAttribute = getAttribute(environment, selection.getName());
                // Build predicate arguments for singular attributes only
                if (fieldAttribute instanceof SingularAttribute) {
                    // Process the orderBy clause
                    mayBeAddOrderBy(selection, query, cb, fieldPath, environment);

                    // Check if it's an object and the foreign side is One.  Then we can eagerly join causing an inner join instead of 2 queries
                    SingularAttribute<?, ?> attribute = (SingularAttribute<?, ?>) fieldAttribute;
                    if (
                        attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.MANY_TO_ONE ||
                        attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ONE_TO_ONE
                    ) {
                        // Let's do fugly conversion
                        Boolean isOptional = getOptionalArgumentValue(environment, selection, attribute);

                        // Let's apply left outer join to retrieve optional associations
                        if (!isOptional || !whereArgument.isPresent()) {
                            fetch = reuseFetch(from, selection.getName(), isOptional);
                        }
                    } else if (attribute.getPersistentAttributeType() == PersistentAttributeType.EMBEDDED) {
                        // Process where arguments clauses.
                        arguments.addAll(
                            selection
                                .getArguments()
                                .stream()
                                .filter(this::isPredicateArgument)
                                .map(it -> new Argument(selection.getName() + "." + it.getName(), it.getValue()))
                                .collect(Collectors.toList())
                        );
                    }
                } else {
                    GraphQLObjectType objectType = getObjectType(environment);
                    EntityType<?> entityType = getEntityType(objectType);

                    PluralAttribute<?, ?, ?> attribute = (PluralAttribute<?, ?, ?>) entityType.getAttribute(
                        selection.getName()
                    );

                    // We must add plural attributes with explicit join fetch
                    // the many end is a collection, and it is always optional by default (empty collection)
                    Boolean isOptional = getOptionalArgumentValue(environment, selection, attribute);

                    // Let's join fetch element collections to avoid filtering their values used where search criteria
                    if (PersistentAttributeType.ELEMENT_COLLECTION == attribute.getPersistentAttributeType()) {
                        from.fetch(selection.getName(), JoinType.LEFT);
                    } else if (!whereArgument.isPresent() && !hasAnySelectionOrderBy(selection)) {
                        fetch = reuseFetch(from, selection.getName(), isOptional);
                    }
                }
                // Let's build join fetch graph to avoid Hibernate error:
                // "query specified join fetching, but the owner of the fetched association was not present in the select list"
                if (selection.getSelectionSet() != null && fetch != null) {
                    Map<String, Object> variables = environment.getVariables();

                    GraphQLFieldDefinition fieldDefinition = getFieldDefinition(
                        environment.getGraphQLSchema(),
                        this.getObjectType(environment),
                        selection
                    );

                    List<Argument> values = whereArgument
                        .map(Collections::singletonList)
                        .orElse(Collections.emptyList());

                    Map<String, Object> fieldArguments = ValuesResolver.getArgumentValues(
                        fieldDefinition.getArguments(),
                        values,
                        new CoercedVariables(variables),
                        environment.getGraphQlContext(),
                        Locale.ROOT
                    );

                    DataFetchingEnvironment fieldEnvironment = wherePredicateEnvironment(
                        environment,
                        fieldDefinition,
                        fieldArguments
                    );

                    predicates.addAll(getFieldPredicates(selection, query, cb, root, fetch, fieldEnvironment));
                }
            });

        arguments.addAll(field.getArguments());

        arguments
            .stream()
            .filter(this::isPredicateArgument)
            .map(it -> getPredicate(field, cb, root, from, environment, it))
            .filter(it -> it != null)
            .forEach(predicates::add);

        return predicates;
    }

    protected Boolean getOptionalArgumentValue(
        DataFetchingEnvironment environment,
        Field selection,
        Attribute<?, ?> attribute
    ) {
        return getArgument(selection, OPTIONAL)
            .map(it -> getArgumentValue(environment, it, Boolean.class))
            .orElseGet(() -> isOptionalAttribute(attribute));
    }

    /**
     * if query orders are empty, then apply default ascending ordering
     * by root id attribute to prevent paging inconsistencies
     *
     * @param query
     * @param from
     * @param cb
     */
    protected void mayBeAddDefaultOrderBy(CriteriaQuery<?> query, From<?, ?> from, CriteriaBuilder cb) {
        if (query.getOrderList() == null || query.getOrderList().isEmpty()) {
            Optional<AttributePropertyDescriptor> attributePropertyDescriptor = EntityIntrospector
                .introspect(entityType)
                .getPersistentPropertyDescriptors()
                .stream()
                .filter(AttributePropertyDescriptor::hasDefaultOrderBy)
                .findFirst();
            if (!attributePropertyDescriptor.isPresent()) {
                if (hasIdAttribute()) {
                    query.orderBy(cb.asc(from.get(idAttributeName())));
                } else if (hasIdClassAttribue()) {
                    List<Order> orders = Stream
                        .of(idClassAttributeNames())
                        .map(name -> cb.asc(from.get(name)))
                        .collect(Collectors.toList());
                    query.orderBy(orders);
                }
            } else {
                AttributePropertyDescriptor attribute = attributePropertyDescriptor.get();

                GraphQLDefaultOrderBy order = attribute.getDefaultOrderBy().get();
                if (order.asc()) {
                    query.orderBy(cb.asc(from.get(attribute.getName())));
                } else {
                    query.orderBy(cb.desc(from.get(attribute.getName())));
                }
            }
        }
    }

    protected boolean isPredicateArgument(Argument argument) {
        return !isOrderByArgument(argument) && !isOptionalArgument(argument);
    }

    protected boolean isOrderByArgument(Argument argument) {
        return GraphQLJpaSchemaBuilder.ORDER_BY_PARAM_NAME.equals(argument.getName());
    }

    protected boolean isOptionalArgument(Argument argument) {
        return OPTIONAL.equals(argument.getName());
    }

    protected Optional<Argument> getArgument(Field selectedField, String argumentName) {
        return selectedField.getArguments().stream().filter(it -> it.getName().equals(argumentName)).findFirst();
    }

    protected <R extends Attribute<?, ?>> R getAttribute(String attributeName) {
        return (R) entityType.getAttribute(attributeName);
    }

    @SuppressWarnings("unchecked")
    protected Predicate getPredicate(
        Field field,
        CriteriaBuilder cb,
        Root<?> from,
        From<?, ?> path,
        DataFetchingEnvironment environment,
        Argument argument
    ) {
        if (
            isLogicalArgument(argument) ||
            isDistinctArgument(argument) ||
            isPageArgument(argument) ||
            isAfterArgument(argument) ||
            isFirstArgument(argument)
        ) {
            return null;
        } else if (isWhereArgument(argument)) {
            return getWherePredicate(cb, from, path, argumentEnvironment(environment, argument), argument);
        } else if (!argument.getName().contains(".")) {
            Attribute<?, ?> argumentEntityAttribute = getAttribute(environment, argument.getName());

            // If the argument is a list, let's assume we need to join and do an 'in' clause
            if (argumentEntityAttribute instanceof PluralAttribute) {
                // Apply left outer join to retrieve optional associations
                Boolean isFetch = environment.getLocalContext();

                return (
                    isFetch ? reuseFetch(from, argument.getName(), false) : reuseJoin(from, argument.getName(), false)
                ).in(convertValue(environment, argument, argument.getValue()));
            }

            return cb.equal(path.get(argument.getName()), convertValue(environment, argument, argument.getValue()));
        } else {
            if (!argument.getName().endsWith(".where")) {
                Path<?> argumentPath = getCompoundJoinedPath(path, argument.getName(), false);

                return cb.equal(argumentPath, convertValue(environment, argument, argument.getValue()));
            } else {
                String fieldName = argument.getName().split("\\.")[0];

                From<?, ?> join = getCompoundJoin(path, argument.getName(), true);
                Argument where = new Argument(WHERE, argument.getValue());
                Map<String, Object> variables = environment.getVariables();

                GraphQLFieldDefinition fieldDef = getFieldDefinition(
                    environment.getGraphQLSchema(),
                    this.getObjectType(environment),
                    new Field(fieldName)
                );

                Map<String, Object> arguments = (Map<String, Object>) ValuesResolver
                    .getArgumentValues(
                        fieldDef.getArguments(),
                        Collections.singletonList(where),
                        new CoercedVariables(variables),
                        environment.getGraphQlContext(),
                        Locale.ROOT
                    )
                    .get(WHERE);

                return getWherePredicate(
                    cb,
                    from,
                    join,
                    wherePredicateEnvironment(environment, fieldDef, arguments),
                    where
                );
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <R extends Value<?>> R getValue(Argument argument, DataFetchingEnvironment environment) {
        Value<?> value = argument.getValue();

        if (VariableReference.class.isInstance(value)) {
            Object variableValue = getVariableReferenceValue((VariableReference) value, environment);

            GraphQLArgument graphQLArgument = environment
                .getExecutionStepInfo()
                .getFieldDefinition()
                .getArgument(argument.getName());

            return (R) AstValueHelper.astFromValue(variableValue, graphQLArgument.getType());
        }

        return (R) value;
    }

    private EnumValue getOrderByValue(Argument argument, DataFetchingEnvironment environment) {
        Value<?> value = argument.getValue();

        if (VariableReference.class.isInstance(value)) {
            Object variableValue = getVariableReferenceValue((VariableReference) value, environment);
            return EnumValue.newEnumValue(variableValue.toString()).build();
        }
        return (EnumValue) value;
    }

    private Object getVariableReferenceValue(VariableReference variableReference, DataFetchingEnvironment env) {
        return env.getVariables().get(variableReference.getName());
    }

    protected Predicate getWherePredicate(
        CriteriaBuilder cb,
        Root<?> root,
        From<?, ?> path,
        DataFetchingEnvironment environment,
        Argument argument
    ) {
        ObjectValue whereValue = getValue(argument, environment);

        if (whereValue.getChildren().isEmpty()) return cb.conjunction();

        Logical logical = extractLogical(argument);

        Map<String, Object> predicateArguments = new LinkedHashMap<>();
        predicateArguments.put(logical.name(), environment.getArguments());

        DataFetchingEnvironment predicateDataFetchingEnvironment = DataFetchingEnvironmentBuilder
            .newDataFetchingEnvironment(environment)
            .arguments(predicateArguments)
            .build();
        Argument predicateArgument = new Argument(logical.name(), whereValue);

        return getArgumentPredicate(
            cb,
            (path != null) ? path : root,
            predicateDataFetchingEnvironment,
            predicateArgument
        );
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected Predicate getArgumentPredicate(
        CriteriaBuilder cb,
        From<?, ?> from,
        DataFetchingEnvironment environment,
        Argument argument
    ) {
        ObjectValue whereValue = getValue(argument, environment);

        if (whereValue.getChildren().isEmpty()) return cb.disjunction();

        Logical logical = extractLogical(argument);

        List<Predicate> predicates = new ArrayList<>();

        whereValue
            .getObjectFields()
            .stream()
            .filter(it -> Logical.names().contains(it.getName()))
            .map(it -> {
                Map<String, Object> arguments = getFieldArguments(environment, it, argument);

                if (it.getValue() instanceof ArrayValue) {
                    return getArgumentsPredicate(
                        cb,
                        from,
                        argumentEnvironment(environment, arguments),
                        new Argument(it.getName(), it.getValue())
                    );
                }

                return getArgumentPredicate(
                    cb,
                    from,
                    argumentEnvironment(environment, arguments),
                    new Argument(it.getName(), it.getValue())
                );
            })
            .forEach(predicates::add);

        whereValue
            .getObjectFields()
            .stream()
            .filter(it -> !Logical.names().contains(it.getName()))
            .map(it -> {
                Map<String, Object> args = getFieldArguments(environment, it, argument);
                Argument arg = new Argument(it.getName(), it.getValue());

                return getObjectFieldPredicate(environment, cb, from, logical, it, arg, args);
            })
            .filter(predicate -> predicate != null)
            .forEach(predicates::add);

        return getCompoundPredicate(cb, predicates, logical);
    }

    protected Predicate getObjectFieldPredicate(
        DataFetchingEnvironment environment,
        CriteriaBuilder cb,
        From<?, ?> from,
        Logical logical,
        ObjectField objectField,
        Argument argument,
        Map<String, Object> arguments
    ) {
        if (isEntityType(environment)) {
            Attribute<?, ?> attribute = getAttribute(environment, argument.getName());

            // TODO add support for embedded element collection, i.e. attribute.isCollection()
            if (attribute.isAssociation()) {
                GraphQLFieldDefinition fieldDefinition = getFieldDefinition(
                    environment.getGraphQLSchema(),
                    this.getObjectType(environment),
                    new Field(objectField.getName())
                );

                if (Arrays.asList(Logical.EXISTS, Logical.NOT_EXISTS).contains(logical)) {
                    AbstractQuery<?> query = environment.getRoot();
                    Subquery<?> subquery = query.subquery(attribute.getJavaType());
                    From<?, ?> correlation = Root.class.isInstance(from)
                        ? subquery.correlate((Root<?>) from)
                        : subquery.correlate((Join<?, ?>) from);

                    Join<?, ?> correlationJoin = correlation.join(objectField.getName());

                    DataFetchingEnvironment existsEnvironment = DataFetchingEnvironmentBuilder
                        .newDataFetchingEnvironment(environment)
                        .root(subquery)
                        .build();

                    Predicate restriction = getArgumentPredicate(
                        cb,
                        correlationJoin,
                        wherePredicateEnvironment(existsEnvironment, fieldDefinition, arguments),
                        argument
                    );

                    Predicate exists = cb.exists(subquery.select((Join) correlationJoin).where(restriction));

                    return logical == Logical.EXISTS ? exists : cb.not(exists);
                }

                AbstractQuery<?> query = environment.getRoot();
                Boolean isFetch = environment.getLocalContext();
                Boolean isOptional = isOptionalAttribute(attribute);

                From<?, ?> context = (isSubquery(query) || isCountQuery(query) || !isFetch)
                    ? reuseJoin(from, objectField.getName(), isOptional)
                    : reuseFetch(from, objectField.getName(), isOptional);

                return getArgumentPredicate(
                    cb,
                    context,
                    wherePredicateEnvironment(environment, fieldDefinition, arguments),
                    argument
                );
            }
        }

        return getLogicalPredicate(
            objectField.getName(),
            cb,
            from,
            objectField,
            argumentEnvironment(environment, arguments),
            argument
        );
    }

    protected boolean isSubquery(AbstractQuery<?> query) {
        return Subquery.class.isInstance(query);
    }

    protected boolean isCountQuery(AbstractQuery<?> query) {
        return Optional
            .ofNullable(query.getSelection())
            .map(Selection::getJavaType)
            .map(Long.class::equals)
            .orElse(false);
    }

    protected Predicate getArgumentsPredicate(
        CriteriaBuilder cb,
        From<?, ?> from,
        DataFetchingEnvironment environment,
        Argument argument
    ) {
        ArrayValue whereValue = getValue(argument, environment);

        if (whereValue.getValues().isEmpty()) return cb.disjunction();

        Logical logical = extractLogical(argument);

        List<Predicate> predicates = new ArrayList<>();

        List<Map<String, Object>> arguments = environment.getArgument(logical.name());
        List<ObjectValue> values = whereValue
            .getValues()
            .stream()
            .map(ObjectValue.class::cast)
            .collect(Collectors.toList());

        List<SimpleEntry<ObjectValue, Map<String, Object>>> tuples = IntStream
            .range(0, values.size())
            .mapToObj(i -> new SimpleEntry<ObjectValue, Map<String, Object>>(values.get(i), arguments.get(i)))
            .collect(Collectors.toList());

        tuples
            .stream()
            .flatMap(e ->
                e
                    .getKey()
                    .getObjectFields()
                    .stream()
                    .filter(it -> Logical.names().contains(it.getName()))
                    .map(it -> {
                        Map<String, Object> args = e.getValue();
                        Argument arg = new Argument(it.getName(), it.getValue());

                        if (ArrayValue.class.isInstance(it.getValue())) {
                            return getArgumentsPredicate(cb, from, argumentEnvironment(environment, args), arg);
                        }

                        return getArgumentPredicate(cb, from, argumentEnvironment(environment, args), arg);
                    })
            )
            .forEach(predicates::add);

        tuples
            .stream()
            .flatMap(e ->
                e
                    .getKey()
                    .getObjectFields()
                    .stream()
                    .filter(it -> !Logical.names().contains(it.getName()))
                    .map(it -> {
                        Map<String, Object> args = e.getValue();
                        Argument arg = new Argument(it.getName(), it.getValue());

                        return getObjectFieldPredicate(environment, cb, from, logical, it, arg, args);
                    })
            )
            .filter(predicate -> predicate != null)
            .forEach(predicates::add);

        return getCompoundPredicate(cb, predicates, logical);
    }

    private Map<String, Object> getFieldArguments(
        DataFetchingEnvironment environment,
        ObjectField field,
        Argument argument
    ) {
        Map<String, Object> arguments;

        if (environment.getArgument(argument.getName()) instanceof Collection) {
            Collection<Map<String, Object>> list = environment.getArgument(argument.getName());

            arguments =
                list
                    .stream()
                    .filter(args -> args.get(field.getName()) != null)
                    .findFirst()
                    .orElse(list.stream().findFirst().get());
        } else {
            arguments = environment.getArgument(argument.getName());
        }

        return arguments;
    }

    private Logical extractLogical(Argument argument) {
        return Optional
            .of(argument.getName())
            .filter(it -> Logical.names().contains(it))
            .map(it -> Logical.valueOf(it))
            .orElse(Logical.AND);
    }

    private Predicate getLogicalPredicates(
        String fieldName,
        CriteriaBuilder cb,
        From<?, ?> path,
        ObjectField objectField,
        DataFetchingEnvironment environment,
        Argument argument
    ) {
        ArrayValue value = ArrayValue.class.cast(objectField.getValue());

        Logical logical = extractLogical(argument);

        List<Predicate> predicates = new ArrayList<>();

        value
            .getValues()
            .stream()
            .map(ObjectValue.class::cast)
            .flatMap(it -> it.getObjectFields().stream())
            .map(it -> {
                Map<String, Object> args = getFieldArguments(environment, it, argument);
                Argument arg = new Argument(it.getName(), it.getValue());

                return getLogicalPredicate(it.getName(), cb, path, it, argumentEnvironment(environment, args), arg);
            })
            .forEach(predicates::add);

        return getCompoundPredicate(cb, predicates, logical);
    }

    private Predicate getLogicalPredicate(
        String fieldName,
        CriteriaBuilder cb,
        From<?, ?> path,
        ObjectField objectField,
        DataFetchingEnvironment environment,
        Argument argument
    ) {
        ObjectValue expressionValue;

        if (objectField.getValue() instanceof ObjectValue) expressionValue =
            (ObjectValue) objectField.getValue(); else expressionValue = new ObjectValue(Arrays.asList(objectField));

        if (expressionValue.getChildren().isEmpty()) return cb.disjunction();

        Logical logical = extractLogical(argument);

        List<Predicate> predicates = new ArrayList<>();

        // Let's parse logical expressions, i.e. AND, OR
        expressionValue
            .getObjectFields()
            .stream()
            .filter(it -> Logical.names().contains(it.getName()))
            .map(it -> {
                Map<String, Object> args = getFieldArguments(environment, it, argument);
                Argument arg = new Argument(it.getName(), it.getValue());

                if (it.getValue() instanceof ArrayValue) {
                    return getLogicalPredicates(fieldName, cb, path, it, argumentEnvironment(environment, args), arg);
                }

                return getLogicalPredicate(fieldName, cb, path, it, argumentEnvironment(environment, args), arg);
            })
            .forEach(predicates::add);

        // Let's parse relation criteria expressions if present, i.e. books, author, etc.
        if (
            expressionValue
                .getObjectFields()
                .stream()
                .anyMatch(it -> !Logical.names().contains(it.getName()) && !Criteria.names().contains(it.getName()))
        ) {
            GraphQLFieldDefinition fieldDefinition = getFieldDefinition(
                environment.getGraphQLSchema(),
                this.getObjectType(environment),
                new Field(fieldName)
            );
            Map<String, Object> args = new LinkedHashMap<>();
            Argument arg = new Argument(logical.name(), expressionValue);
            boolean isOptional = false;

            if (Logical.names().contains(argument.getName())) {
                args.put(logical.name(), environment.getArgument(argument.getName()));
            } else {
                args.put(logical.name(), environment.getArgument(fieldName));

                isOptional = isOptionalAttribute(getAttribute(environment, argument.getName()));
            }

            return getArgumentPredicate(
                cb,
                reuseJoin(path, fieldName, isOptional),
                wherePredicateEnvironment(environment, fieldDefinition, args),
                arg
            );
        }

        // Let's parse simple Criteria expressions, i.e. EQ, LIKE, etc.
        JpaPredicateBuilder pb = new JpaPredicateBuilder(cb);

        expressionValue
            .getObjectFields()
            .stream()
            .filter(it -> Criteria.names().contains(it.getName()))
            .map(it ->
                getPredicateFilter(
                    new ObjectField(fieldName, it.getValue()),
                    argumentEnvironment(environment, argument),
                    new Argument(it.getName(), it.getValue())
                )
            )
            .sorted()
            .map(it -> pb.getPredicate(path, path.get(it.getField()), it))
            .filter(predicate -> predicate != null)
            .forEach(predicates::add);

        return getCompoundPredicate(cb, predicates, logical);
    }

    private Predicate getCompoundPredicate(CriteriaBuilder cb, List<Predicate> predicates, Logical logical) {
        if (predicates.isEmpty()) return cb.disjunction();

        if (predicates.size() == 1) {
            return predicates.get(0);
        }

        return (logical == Logical.OR)
            ? cb.or(predicates.toArray(new Predicate[0]))
            : cb.and(predicates.toArray(new Predicate[0]));
    }

    private PredicateFilter getPredicateFilter(
        ObjectField objectField,
        DataFetchingEnvironment environment,
        Argument argument
    ) {
        EnumSet<PredicateFilter.Criteria> options = EnumSet.of(PredicateFilter.Criteria.valueOf(argument.getName()));

        Map<String, Object> valueArguments = new LinkedHashMap<String, Object>();
        valueArguments.put(objectField.getName(), environment.getArgument(argument.getName()));

        DataFetchingEnvironment dataFetchingEnvironment = DataFetchingEnvironmentBuilder
            .newDataFetchingEnvironment(environment)
            .arguments(valueArguments)
            .build();

        Argument dataFetchingArgument = new Argument(objectField.getName(), argument.getValue());

        Object filterValue = convertValue(dataFetchingEnvironment, dataFetchingArgument, argument.getValue());

        Attribute attribute = getAttribute(environment, objectField.getName());

        return new PredicateFilter(objectField.getName(), filterValue, options, attribute);
    }

    protected DataFetchingEnvironment argumentEnvironment(
        DataFetchingEnvironment environment,
        Map<String, Object> arguments
    ) {
        return DataFetchingEnvironmentBuilder.newDataFetchingEnvironment(environment).arguments(arguments).build();
    }

    protected DataFetchingEnvironment argumentEnvironment(DataFetchingEnvironment environment, Argument argument) {
        Map<String, Object> arguments = environment.getArgument(argument.getName());

        return DataFetchingEnvironmentBuilder.newDataFetchingEnvironment(environment).arguments(arguments).build();
    }

    protected DataFetchingEnvironment wherePredicateEnvironment(
        DataFetchingEnvironment environment,
        GraphQLFieldDefinition fieldDefinition,
        Map<String, Object> arguments
    ) {
        return DataFetchingEnvironmentBuilder
            .newDataFetchingEnvironment(environment)
            .arguments(arguments)
            .fieldDefinition(fieldDefinition)
            .fieldType(fieldDefinition.getType())
            .build();
    }

    /**
     * @param fieldName
     * @return Path of compound field to the primitive type
     */
    private From<?, ?> getCompoundJoin(From<?, ?> rootPath, String fieldName, boolean outer) {
        String[] compoundField = fieldName.split("\\.");

        From<?, ?> join;

        if (compoundField.length == 1) {
            return rootPath;
        } else {
            join = reuseJoin(rootPath, compoundField[0], outer);
        }

        for (int i = 1; i < compoundField.length; i++) {
            if (i < (compoundField.length - 1)) {
                join = reuseJoin(join, compoundField[i], outer);
            } else {
                return join;
            }
        }

        return null;
    }

    /**
     * @param fieldName
     * @return Path of compound field to the primitive type
     */
    private Path<?> getCompoundJoinedPath(From<?, ?> rootPath, String fieldName, boolean outer) {
        String[] compoundField = fieldName.split("\\.");

        From<?, ?> join;

        if (compoundField.length == 1) {
            return rootPath.get(compoundField[0]);
        } else {
            join = reuseJoin(rootPath, compoundField[0], outer);
        }

        for (int i = 1; i < compoundField.length; i++) {
            if (i < (compoundField.length - 1)) {
                join = reuseJoin(join, compoundField[i], outer);
            } else {
                return join.get(compoundField[i]);
            }
        }

        return null;
    }

    // trying to find already existing joins to reuse
    private From<?, ?> reuseJoin(From<?, ?> from, String fieldName, boolean outer) {
        for (Join<?, ?> join : from.getJoins()) {
            if (join.getAttribute().getName().equals(fieldName)) {
                return join;
            }
        }
        return outer ? from.join(fieldName, JoinType.LEFT) : from.join(fieldName);
    }

    // trying to find already existing fetch joins to reuse
    private From<?, ?> reuseFetch(From<?, ?> from, String fieldName, boolean outer) {
        for (Fetch<?, ?> fetch : from.getFetches()) {
            if (fetch.getAttribute().getName().equals(fieldName)) {
                return (From<?, ?>) fetch;
            }
        }
        return outer ? (From<?, ?>) from.fetch(fieldName, JoinType.LEFT) : (From<?, ?>) from.fetch(fieldName);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected Object convertValue(DataFetchingEnvironment environment, Argument argument, Value value) {
        if (value instanceof NullValue) {
            return value;
        } else if (value instanceof StringValue) {
            Object convertedValue = environment.getArgument(argument.getName());
            if (convertedValue != null) {
                Class javaType = getJavaType(environment, argument);
                // Return real typed resolved value even if the Value is a StringValue
                return javaType.isInstance(convertedValue)
                    ? convertedValue
                    : JavaScalars.of(javaType).getCoercing().parseValue(convertedValue);
            } else {
                // Return provided StringValue
                return ((StringValue) value).getValue();
            }
        } else if (value instanceof VariableReference variableReference) {
            Class javaType = getJavaType(environment, argument);
            Object argumentValue = environment.getVariables().get(variableReference.getName());

            if (javaType.isEnum()) {
                if (argumentValue instanceof Collection argumentValues) {
                    List<Enum> values = new ArrayList<>();

                    argumentValues.forEach(it -> values.add(Enum.valueOf(javaType, it.toString())));

                    return values;
                } else {
                    return Enum.valueOf(javaType, argumentValue.toString());
                }
            } else {
                Coercing<?, ?> coercing = JavaScalars.of(javaType).getCoercing();
                Function<Object, Object> valueConverter = it -> javaType.isInstance(it) ? it : coercing.parseValue(it);

                if (argumentValue instanceof Collection<?> argumentValues) {
                    return argumentValues.stream().map(valueConverter).toList();
                } else {
                    // Get resolved variable in environment arguments
                    return valueConverter.apply(argumentValue);
                }
            }
        } else if (value instanceof ArrayValue) {
            Collection arrayValue = environment.getArgument(argument.getName());

            if (arrayValue != null) {
                // Let's unwrap array of array values
                if (arrayValue.stream().allMatch(it -> it instanceof Collection)) {
                    arrayValue = Collection.class.cast(arrayValue.iterator().next());
                }

                // Let's convert enum types, i.e. array of strings or EnumValue into Java type
                if (getJavaType(environment, argument).isEnum()) {
                    Function<Object, Value> objectValue = obj ->
                        Value.class.isInstance(obj) ? Value.class.cast(obj) : new EnumValue(obj.toString());
                    // Return real typed resolved array values converted into Java enums
                    return arrayValue
                        .stream()
                        .map(it -> convertValue(environment, argument, objectValue.apply(it)))
                        .collect(Collectors.toList());
                }
                // Let's try handle Ast Value types
                else if (arrayValue.stream().anyMatch(it -> it instanceof Value)) {
                    return arrayValue
                        .stream()
                        .map(it -> convertValue(environment, argument, Value.class.cast(it)))
                        .collect(Collectors.toList());
                }
                // Return real typed resolved array value, i.e. Date, UUID, Long
                else {
                    return arrayValue;
                }
            } else {
                // Wrap converted values in ArrayList
                return ((ArrayValue) value).getValues()
                    .stream()
                    .map(it -> convertValue(environment, argument, it))
                    .collect(Collectors.toList());
            }
        } else if (value instanceof EnumValue) {
            Class enumType = getJavaType(environment, argument);
            return Enum.valueOf(enumType, ((EnumValue) value).getName());
        } else if (value instanceof IntValue) {
            return ((IntValue) value).getValue();
        } else if (value instanceof BooleanValue) {
            return ((BooleanValue) value).isValue();
        } else if (value instanceof FloatValue) {
            return ((FloatValue) value).getValue();
        } else if (value instanceof ObjectValue) {
            Class javaType = getJavaType(environment, argument);
            Map<String, Object> values = environment.getArgument(argument.getName());

            try {
                return getJavaBeanValue(javaType, values);
            } catch (Exception cause) {
                throw new RuntimeException(cause);
            }
        }

        return value;
    }

    private Object getJavaBeanValue(Class<?> javaType, Map<String, Object> values) throws Exception {
        Constructor<?> constructor = javaType.getConstructor();
        constructor.setAccessible(true);

        Object javaBean = constructor.newInstance();

        values
            .entrySet()
            .stream()
            .forEach(entry -> {
                setPropertyValue(javaBean, entry.getKey(), entry.getValue());
            });

        return javaBean;
    }

    private void setPropertyValue(Object javaBean, String propertyName, Object propertyValue) {
        try {
            BeanInfo bi = Introspector.getBeanInfo(javaBean.getClass());
            PropertyDescriptor pds[] = bi.getPropertyDescriptors();
            for (PropertyDescriptor pd : pds) {
                if (pd.getName().equals(propertyName)) {
                    Method setter = pd.getWriteMethod();
                    setter.setAccessible(true);

                    if (setter != null) {
                        setter.invoke(javaBean, new Object[] { propertyValue });
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    /**
     * Resolve Java type from associated query argument JPA model attribute
     *
     * @param environment
     * @param argument
     * @return Java class type
     */
    protected Class<?> getJavaType(DataFetchingEnvironment environment, Argument argument) {
        Attribute<?, ?> argumentEntityAttribute = getAttribute(environment, argument.getName());

        if (argumentEntityAttribute instanceof PluralAttribute) return (
            (PluralAttribute<?, ?, ?>) argumentEntityAttribute
        ).getElementType()
            .getJavaType();

        return argumentEntityAttribute.getJavaType();
    }

    /**
     * Resolve JPA model persistent attribute from query argument instance
     *
     * @param environment
     * @param argument
     * @return JPA model attribute
     */
    private Attribute<?, ?> getAttribute(DataFetchingEnvironment environment, String argument) {
        GraphQLObjectType objectType = getObjectType(environment);
        if (!isEntityType(objectType)) {
            return getEmbeddableType(objectType).getAttribute(argument);
        }

        return getEntityType(objectType).getAttribute(argument);
    }

    private boolean isOptionalAttribute(Attribute<?, ?> attribute) {
        if (SingularAttribute.class.isInstance(attribute)) {
            return SingularAttribute.class.cast(attribute).isOptional();
        } else if (PluralAttribute.class.isInstance(attribute)) {
            return true;
        }

        return false;
    }

    /**
     * Resolve JPA model entity type from GraphQL objectType
     *
     * @param objectType
     * @return JPA model entity type or null if there is no match.
     */
    private EntityType<?> getEntityType(GraphQLObjectType objectType) {
        return entityTypeMap.computeIfAbsent(objectType, this::computeEntityType);
    }

    private boolean isEntityType(DataFetchingEnvironment environment) {
        GraphQLObjectType objectType = getObjectType(environment);
        return isEntityType(objectType);
    }

    private boolean isEntityType(GraphQLObjectType objectType) {
        return getEntityType(objectType) != null;
    }

    private EntityType<?> computeEntityType(GraphQLObjectType objectType) {
        return graphQLObjectTypeMetadata.entity(objectType.getName());
    }

    private EmbeddableType<?> getEmbeddableType(GraphQLObjectType objectType) {
        return embeddableTypeMap.computeIfAbsent(objectType, this::computeEmbeddableType);
    }

    private EmbeddableType<?> computeEmbeddableType(GraphQLObjectType objectType) {
        return graphQLObjectTypeMetadata.embeddable(objectType.getName());
    }

    /**
     * Resolve GraphQL object type from Argument output type.
     *
     * @param environment
     * @return resolved GraphQL object type or null if no output type is provided
     */
    private GraphQLObjectType getObjectType(DataFetchingEnvironment environment) {
        return getObjectType(environment.getFieldType());
    }

    private GraphQLObjectType getObjectType(GraphQLType outputType) {
        if (outputType instanceof GraphQLList) outputType = ((GraphQLList) outputType).getWrappedType();

        if (outputType instanceof GraphQLObjectType) return (GraphQLObjectType) outputType;

        return null;
    }

    protected Optional<Argument> extractArgument(Field field, String argumentName) {
        return field.getArguments().stream().filter(it -> argumentName.equals(it.getName())).findFirst();
    }

    protected Argument extractArgument(Field field, String argumentName, Value defaultValue) {
        return extractArgument(field, argumentName).orElse(new Argument(argumentName, defaultValue));
    }

    protected GraphQLFieldDefinition getFieldDefinition(
        GraphQLSchema schema,
        GraphQLObjectType parentType,
        Field field
    ) {
        if (schema.getQueryType() == parentType) {
            if (field.getName().equals(SchemaMetaFieldDef.getName())) {
                return SchemaMetaFieldDef;
            }
            if (field.getName().equals(TypeMetaFieldDef.getName())) {
                return TypeMetaFieldDef;
            }
        }
        if (field.getName().equals(TypeNameMetaFieldDef.getName())) {
            return TypeNameMetaFieldDef;
        }

        GraphQLFieldDefinition fieldDefinition = parentType.getFieldDefinition(field.getName());

        if (fieldDefinition != null) {
            return fieldDefinition;
        }

        throw new GraphQLException("unknown field " + field.getName());
    }

    protected boolean hasSelectionSet(Field field) {
        return field.getSelectionSet() != null;
    }

    @SuppressWarnings("unchecked")
    protected <T> T getArgumentValue(DataFetchingEnvironment environment, Argument argument, Class<T> type) {
        Value<?> value = argument.getValue();

        if (VariableReference.class.isInstance(value)) {
            return (T) environment.getVariables().get(VariableReference.class.cast(value).getName());
        } else if (BooleanValue.class.isInstance(value)) {
            return (T) Boolean.valueOf(BooleanValue.class.cast(value).isValue());
        } else if (IntValue.class.isInstance(value)) {
            return (T) IntValue.class.cast(value).getValue();
        } else if (StringValue.class.isInstance(value)) {
            return (T) StringValue.class.cast(value).getValue();
        } else if (FloatValue.class.isInstance(value)) {
            return (T) FloatValue.class.cast(value).getValue();
        } else if (NullValue.class.isInstance(value)) {
            return null;
        }

        throw new IllegalArgumentException("Not supported");
    }

    protected boolean isPersistent(DataFetchingEnvironment environment, String attributeName) {
        GraphQLObjectType objectType = getObjectType(environment);
        EntityType<?> entityType = getEntityType(objectType);

        return isPersistent(entityType, attributeName);
    }

    protected boolean isPersistent(EntityType<?> entityType, String attributeName) {
        try {
            return entityType.getAttribute(attributeName) != null;
        } catch (Exception ignored) {}

        return false;
    }

    protected String getJPQLQueryString(TypedQuery<?> query) {
        try {
            Method getQueryString = ReflectionUtil.getMethod(query.getClass(), "getQueryString");
            if (getQueryString != null) {
                return getQueryString.invoke(query).toString();
            }
        } catch (Exception ignored) {
            logger.error("Error getting JPQL string", ignored);
        }

        return null;
    }

    protected boolean hasIdAttribute() {
        return entityType.getIdType() != null;
    }

    protected String idAttributeName() {
        return entityType.getId(entityType.getIdType().getJavaType()).getName();
    }

    protected boolean hasIdClassAttribue() {
        return entityType.getIdClassAttributes() != null;
    }

    protected String[] idClassAttributeNames() {
        return entityType
            .getIdClassAttributes()
            .stream()
            .map(SingularAttribute::getName)
            .sorted()
            .collect(Collectors.toList())
            .toArray(new String[0]);
    }

    protected <T> T getParentIdAttributeValue(T entity) {
        SingularAttribute<?, Object> parentIdAttribute = entityType.getId(Object.class);

        return (T) getAttributeValue(entity, parentIdAttribute);
    }

    /**
     * Fetches the value of the given SingularAttribute on the given
     * entity.
     *
     * http://stackoverflow.com/questions/7077464/how-to-get-singularattribute-mapped-value-of-a-persistent-object
     */
    @SuppressWarnings("unchecked")
    protected <E, T> T getAttributeValue(T entity, SingularAttribute<E, T> field) {
        try {
            Member member = field.getJavaMember();
            if (member instanceof Method) {
                // this should be a getter method:
                return (T) ((Method) member).invoke(entity);
            } else if (member instanceof java.lang.reflect.Field) {
                return (T) ((java.lang.reflect.Field) member).get(entity);
            } else {
                throw new IllegalArgumentException(
                    "Unexpected java member type. Expecting method or field, found: " + member
                );
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Fetches the value of the given SingularAttribute on the given
     * entity.
     *
     * http://stackoverflow.com/questions/7077464/how-to-get-singularattribute-mapped-value-of-a-persistent-object
     */
    @SuppressWarnings("unchecked")
    protected <EntityType, FieldType> FieldType getAttributeValue(
        EntityType entity,
        PluralAttribute<EntityType, ?, FieldType> field
    ) {
        try {
            Member member = field.getJavaMember();
            if (member instanceof Method) {
                // this should be a getter method:
                return (FieldType) ((Method) member).invoke(entity);
            } else if (member instanceof java.lang.reflect.Field) {
                return (FieldType) ((java.lang.reflect.Field) member).get(entity);
            } else {
                throw new IllegalArgumentException(
                    "Unexpected java member type. Expecting method or field, found: " + member
                );
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected boolean resolveDistinctArgument(Field field) {
        Argument distinctArg = extractArgument(field, SELECT_DISTINCT_PARAM_NAME, new BooleanValue(defaultDistinct));

        return BooleanValue.class.cast(distinctArg.getValue()).isValue();
    }

    public boolean isDefaultDistinct() {
        return defaultDistinct;
    }

    public String getSelectNodeName() {
        return selectNodeName;
    }

    public MergedField resolveQueryField(Field rootNode) {
        Optional<Field> recordsSelection = GraphQLSupport.searchByFieldName(rootNode, getSelectNodeName());

        Field queryField = recordsSelection
            .map(selectNode ->
                Field
                    .newField(selectNode.getName())
                    .selectionSet(selectNode.getSelectionSet())
                    .arguments(rootNode.getArguments())
                    .directives(selectNode.getDirectives())
                    .build()
            )
            .orElse(rootNode);

        return MergedField.newMergedField(queryField).build();
    }

    public GraphQLObjectType getEntityObjectType() {
        return entityObjectType;
    }

    public int getDefaultFetchSize() {
        return defaultFetchSize;
    }

    private MergedField flattenEmbeddedIdArguments(Field field) {
        // manage object arguments (EmbeddedId)
        final List<Argument> argumentsWhereObjectsAreFlattened = field
            .getArguments()
            .stream()
            .flatMap(argument -> {
                if (
                    !isWhereArgument(argument) &&
                    !isPageArgument(argument) &&
                    argument.getValue() instanceof ObjectValue
                ) {
                    return ((ObjectValue) argument.getValue()).getObjectFields()
                        .stream()
                        .map(objectField ->
                            new Argument(argument.getName() + "." + objectField.getName(), objectField.getValue())
                        );
                } else {
                    return Stream.of(argument);
                }
            })
            .collect(Collectors.toList());

        return MergedField
            .newMergedField(field.transform(builder -> builder.arguments(argumentsWhereObjectsAreFlattened)))
            .build();
    }

    protected boolean hasAnySelectionOrderBy(Field field) {
        if (!hasSelectionSet(field)) return false;

        // Loop through all of the fields being requested
        return field
            .getSelectionSet()
            .getSelections()
            .stream()
            .filter(Field.class::isInstance)
            .map(Field.class::cast)
            .anyMatch(selectedField -> {
                // Optional orderBy argument
                Optional<Argument> orderBy = selectedField
                    .getArguments()
                    .stream()
                    .filter(this::isOrderByArgument)
                    .findFirst();

                if (orderBy.isPresent()) {
                    return true;
                }

                return false;
            });
    }

    private <T> T unproxy(T entityProxy) {
        return (T) unproxy.apply(entityProxy);
    }

    private <T> T unproxyAndThenDetach(T entityProxy) {
        return (T) unproxy.andThen(this::detach).apply(entityProxy);
    }

    private <T> T detach(T entity) {
        entityManager.detach(entity);

        return entity;
    }

    EntityGraph<?> createEntityGraph(DataFetchingEnvironment environment) {
        Field root = environment.getMergedField().getSingleField();
        GraphQLObjectType fieldType = getObjectType(environment);
        EntityType<?> entityType = getEntityType(fieldType);

        EntityGraph<?> entityGraph = entityManager.createEntityGraph(entityType.getJavaType());

        var entityDescriptor = EntityIntrospector.introspect(entityType);

        selections(root)
            .forEach(selectedField -> {
                var propertyDescriptor = entityDescriptor.getPropertyDescriptor(selectedField.getName());

                propertyDescriptor
                    .flatMap(AttributePropertyDescriptor::getAttribute)
                    .ifPresent(attribute -> {
                        if (
                            isManagedType(attribute) && hasSelectionSet(selectedField) && hasNoArguments(selectedField)
                        ) {
                            var attributeFieldDefinition = fieldType.getFieldDefinition(attribute.getName());
                            entityGraph.addAttributeNodes(attribute.getName());
                            addSubgraph(
                                selectedField,
                                attributeFieldDefinition,
                                entityGraph.addSubgraph(attribute.getName())
                            );
                        } else if (isBasic(attribute)) {
                            entityGraph.addAttributeNodes(attribute.getName());
                        }
                    });
            });

        return entityGraph;
    }

    void addSubgraph(Field field, GraphQLFieldDefinition fieldDefinition, Subgraph<?> subgraph) {
        var fieldObjectType = getObjectType(fieldDefinition.getType());
        var fieldEntityType = getEntityType(fieldObjectType);
        var fieldEntityDescriptor = EntityIntrospector.introspect(fieldEntityType);

        selections(field)
            .forEach(selectedField -> {
                var propertyDescriptor = fieldEntityDescriptor.getPropertyDescriptor(selectedField.getName());

                propertyDescriptor
                    .flatMap(AttributePropertyDescriptor::getAttribute)
                    .ifPresent(attribute -> {
                        var selectedName = selectedField.getName();

                        if (
                            hasSelectionSet(selectedField) && isManagedType(attribute) && hasNoArguments(selectedField)
                        ) {
                            var selectedFieldDefinition = fieldObjectType.getFieldDefinition(selectedName);
                            subgraph.addAttributeNodes(selectedName);
                            addSubgraph(selectedField, selectedFieldDefinition, subgraph.addSubgraph(selectedName));
                        } else if (isBasic(attribute)) {
                            subgraph.addAttributeNodes(selectedName);
                        }
                    });
            });
    }

    static boolean isManagedType(Attribute<?, ?> attribute) {
        return (
            attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.EMBEDDED &&
            attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.BASIC &&
            attribute.getPersistentAttributeType() != Attribute.PersistentAttributeType.ELEMENT_COLLECTION
        );
    }

    static boolean isBasic(Attribute<?, ?> attribute) {
        return !isManagedType(attribute);
    }

    static boolean hasNoArguments(Field field) {
        return !hasArguments(field);
    }

    static boolean hasArguments(Field field) {
        return field.getArguments() != null && !field.getArguments().isEmpty();
    }

    /**
     * Creates builder to build {@link GraphQLJpaQueryFactory}.
     * @return created builder
     */
    public static IEntityManagerStage builder() {
        return new Builder();
    }

    /**
     * Definition of a stage for staged builder.
     */
    public interface IEntityManagerStage {
        /**
         * Builder method for entityManager parameter.
         * @param entityManager field to set
         * @return builder
         */
        public IEntityTypeStage withEntityManager(EntityManager entityManager);
    }

    /**
     * Definition of a stage for staged builder.
     */
    public interface IEntityTypeStage {
        /**
         * Builder method for entityType parameter.
         * @param entityType field to set
         * @return builder
         */
        public IGraphQLObjectTypeMetadataStage withEntityType(EntityType<?> entityType);
    }

    public interface IGraphQLObjectTypeMetadataStage {
        /**
         * Builder method for graphQLObjectTypeMetadata parameter.
         * @param graphQLObjectTypeMetadata metadata
         * @return builder
         */
        public IEntityObjectTypeStage withGraphQLObjectTypeMetadata(
            GraphQLObjectTypeMetadata graphQLObjectTypeMetadata
        );
    }

    /**
     * Definition of a stage for staged builder.
     */
    public interface IEntityObjectTypeStage {
        /**
         * Builder method for entityObjectType parameter.
         * @param entityObjectType field to set
         * @return builder
         */
        public ISelectNodeNameStage withEntityObjectType(GraphQLObjectType entityObjectType);
    }

    /**
     * Definition of a stage for staged builder.
     */
    public interface ISelectNodeNameStage {
        /**
         * Builder method for selectNodeName parameter.
         * @param selectNodeName field to set
         * @return builder
         */
        public IBuildStage withSelectNodeName(String selectNodeName);
    }

    /**
     * Definition of a stage for staged builder.
     */
    public interface IBuildStage {
        /**
         * Builder method for toManyDefaultOptional parameter.
         * @param toManyDefaultOptional field to set
         * @return builder
         */
        IBuildStage withToManyDefaultOptional(boolean toManyDefaultOptional);

        /**
         * Builder method for defaultDistinct parameter.
         * @param defaultDistinct field to set
         * @return builder
         */
        IBuildStage withDefaultDistinct(boolean defaultDistinct);

        /**
         * Builder method for resultStream parameter.
         * @param resultStream field to set
         * @return builder
         */
        IBuildStage withResultStream(boolean resultStream);

        /**
         * Builder method for defaultFetchSize parameter.
         * @param defaultFetchSize field to set
         * @return builder
         */
        IBuildStage withDefaultFetchSize(int defaultFetchSize);

        /**
         * Builder method for restrictedKeysProvider parameter.
         * @param restrictedKeysProvider field to set
         * @return builder
         */
        IBuildStage withRestrictedKeysProvider(RestrictedKeysProvider restrictedKeysProvider);

        /**
         * Builder method of the builder.
         * @return built class
         */
        GraphQLJpaQueryFactory build();
    }

    /**
     * Builder to build {@link GraphQLJpaQueryFactory}.
     */
    public static final class Builder
        implements
            IEntityManagerStage,
            IEntityTypeStage,
            IGraphQLObjectTypeMetadataStage,
            IEntityObjectTypeStage,
            ISelectNodeNameStage,
            IBuildStage {

        private RestrictedKeysProvider restrictedKeysProvider;
        private EntityManager entityManager;
        private EntityType<?> entityType;
        private GraphQLObjectType entityObjectType;
        private String selectNodeName;
        private boolean toManyDefaultOptional = true;
        private boolean defaultDistinct = true;
        private int defaultFetchSize = 100;
        private boolean resultStream = false;
        private GraphQLObjectTypeMetadata graphQLObjectTypeMetadata;

        private Builder() {}

        @Override
        public IEntityTypeStage withEntityManager(EntityManager entityManager) {
            this.entityManager = entityManager;
            return this;
        }

        @Override
        public IGraphQLObjectTypeMetadataStage withEntityType(EntityType<?> entityType) {
            this.entityType = entityType;
            return this;
        }

        @Override
        public IEntityObjectTypeStage withGraphQLObjectTypeMetadata(
            GraphQLObjectTypeMetadata graphQLObjectTypeMetadata
        ) {
            this.graphQLObjectTypeMetadata = graphQLObjectTypeMetadata;

            return this;
        }

        @Override
        public ISelectNodeNameStage withEntityObjectType(GraphQLObjectType entityObjectType) {
            this.entityObjectType = entityObjectType;
            return this;
        }

        @Override
        public IBuildStage withSelectNodeName(String selectNodeName) {
            this.selectNodeName = selectNodeName;
            return this;
        }

        @Override
        public IBuildStage withToManyDefaultOptional(boolean toManyDefaultOptional) {
            this.toManyDefaultOptional = toManyDefaultOptional;
            return this;
        }

        @Override
        public IBuildStage withDefaultDistinct(boolean defaultDistinct) {
            this.defaultDistinct = defaultDistinct;
            return this;
        }

        @Override
        public IBuildStage withResultStream(boolean resultStream) {
            this.resultStream = resultStream;
            return this;
        }

        @Override
        public IBuildStage withDefaultFetchSize(int defaultFetchSize) {
            this.defaultFetchSize = defaultFetchSize;
            return this;
        }

        @Override
        public IBuildStage withRestrictedKeysProvider(RestrictedKeysProvider restrictedKeysProvider) {
            this.restrictedKeysProvider = restrictedKeysProvider;
            return this;
        }

        @Override
        public GraphQLJpaQueryFactory build() {
            Objects.requireNonNull(restrictedKeysProvider, "restrictedKeysProvider must not be null");

            return new GraphQLJpaQueryFactory(this);
        }
    }

    public RestrictedKeysProvider getRestrictedKeysProvider() {
        return restrictedKeysProvider;
    }
}
