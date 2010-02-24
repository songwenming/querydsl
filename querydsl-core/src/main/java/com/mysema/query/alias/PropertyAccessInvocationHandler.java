/*
 * Copyright (c) 2009 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.query.alias;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import org.apache.commons.lang.StringUtils;
import org.slf4j.LoggerFactory;

import sun.util.logging.resources.logging;

import com.mysema.query.types.expr.ECollection;
import com.mysema.query.types.expr.EMap;
import com.mysema.query.types.expr.Expr;
import com.mysema.query.types.path.PBoolean;
import com.mysema.query.types.path.PCollection;
import com.mysema.query.types.path.PComparable;
import com.mysema.query.types.path.PDate;
import com.mysema.query.types.path.PDateTime;
import com.mysema.query.types.path.PEntity;
import com.mysema.query.types.path.PList;
import com.mysema.query.types.path.PMap;
import com.mysema.query.types.path.PNumber;
import com.mysema.query.types.path.PSet;
import com.mysema.query.types.path.PSimple;
import com.mysema.query.types.path.PString;
import com.mysema.query.types.path.PTime;
import com.mysema.query.types.path.Path;
import com.mysema.query.types.path.PathMetadata;
import com.mysema.query.types.path.PathMetadataFactory;

/**
 * PropertyAccessInvocationHandler is the main InvocationHandler class for the
 * CGLIB alias proxies
 * 
 * @author tiwe
 * @version $Id$
 */
class PropertyAccessInvocationHandler implements MethodInterceptor {

    private final Expr<?> path;

    private final AliasFactory aliasFactory;

    private final Map<Object, Expr<?>> propToExpr = new HashMap<Object, Expr<?>>();

    private final Map<Object, Object> propToObj = new HashMap<Object, Object>();

    public PropertyAccessInvocationHandler(Expr<?> path, AliasFactory aliasFactory) {
        this.path = path;
        this.aliasFactory = aliasFactory;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private Class<?> getTypeParameter(Type type, int index) {
        if (type instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType) type;
            Type[] targs = ptype.getActualTypeArguments();
            if (targs[index] instanceof WildcardType) {
                WildcardType wildcardType = (WildcardType) targs[index];
                return (Class<?>) wildcardType.getUpperBounds()[0];
            } else if (targs[index] instanceof TypeVariable) {
                return (Class<?>) ((TypeVariable) targs[index]).getGenericDeclaration();
            } else if (targs[index] instanceof ParameterizedType) {
                return (Class<?>) ((ParameterizedType) targs[index]).getRawType();
            } else {
                try {
                    return (Class<?>) targs[index];
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    public Object intercept(Object proxy, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
        Object rv = null;
        
        MethodType methodType = MethodType.get(method);
       
        if (methodType == MethodType.GETTER) {
            String ptyName = propertyNameForGetter(method);
            Class<?> ptyClass = method.getReturnType();
            Type genericType = method.getGenericReturnType();

            if (propToObj.containsKey(ptyName)) {
                rv = propToObj.get(ptyName);
            } else {
                PathMetadata<String> pm = PathMetadataFactory.forProperty((Path<?>) path, ptyName);
                rv = newInstance(ptyClass, genericType, proxy, ptyName, pm);
            }
            aliasFactory.setCurrent(propToExpr.get(ptyName));

//        } else if (methodType == MethodType.SIZE) {
//            Object propKey = "_size";
//            if (propToObj.containsKey(propKey)) {
//                rv = propToObj.get(propKey);
//            } else {
//                PathMetadata<Integer> pm = PathMetadata.forSize((PCollection<?>) path);
//                rv = newInstance(Integer.class, Integer.class, proxy, propKey, pm);
//            }
//            aliasFactory.setCurrent(propToExpr.get(propKey));

        } else if (methodType == MethodType.LIST_ACCESS) {
            // TODO : manage cases where the argument is based on a property invocation
            Object propKey = Arrays.asList(MethodType.LIST_ACCESS, args[0]);
            if (propToObj.containsKey(propKey)) {
                rv = propToObj.get(propKey);
            } else {
                PathMetadata<Integer> pm = PathMetadataFactory.forListAccess((PList<?, ?>) path, (Integer) args[0]);
                Class<?> elementType = ((ECollection<?,?>) path).getElementType();
                if (elementType != null) {
                    rv = newInstance(elementType, elementType, proxy, propKey, pm);
                } else {
                    rv = newInstance(method.getReturnType(), method.getGenericReturnType(), proxy, propKey, pm);
                }
            }
            aliasFactory.setCurrent(propToExpr.get(propKey));

        } else if (methodType == MethodType.MAP_ACCESS) {
            Object propKey = Arrays.asList(MethodType.MAP_ACCESS, args[0]);
            if (propToObj.containsKey(propKey)) {
                rv = propToObj.get(propKey);
            } else {
                PathMetadata<?> pm = PathMetadataFactory.forMapAccess((PMap<?, ?, ?>) path, args[0]);
                Class<?> valueType = ((EMap<?, ?>) path).getValueType();
                if (valueType != null) {
                    rv = newInstance(valueType, valueType, proxy, propKey, pm);
                } else {
                    rv = newInstance(method.getReturnType(), method.getGenericReturnType(), proxy, propKey, pm);
                }
            }
            aliasFactory.setCurrent(propToExpr.get(propKey));

        } else if (methodType == MethodType.TO_STRING) {
            rv = path.toString();

        } else if (methodType == MethodType.HASH_CODE) {
            rv = path.hashCode();

        } else if (methodType == MethodType.GET_MAPPED_PATH) {
            rv = path;

        } else {
            throw new IllegalArgumentException("Invocation of " + method.getName() + " not supported");
        }
        return rv;
    }

    @SuppressWarnings({ "unchecked", "serial" })
    @Nullable
    private <T> T newInstance(Class<T> type, Type genericType, Object parent, Object propKey, PathMetadata<?> pm) {
        Expr<?> path;
        Object rv;

        if (String.class.equals(type)) {
            path = new PString(pm);
            // null is used as a return value to block method invocations on Strings
            rv = null;

        } else if (Integer.class.equals(type) || int.class.equals(type)) {
            path = new PNumber<Integer>(Integer.class, pm);
            rv = Integer.valueOf(42);

        } else if (java.util.Date.class.equals(type)) {
            path = new PDateTime<Date>(Date.class, pm);
            rv = new Date();
            
        } else if (java.sql.Timestamp.class.equals(type)) {
            path = new PDateTime<Timestamp>(Timestamp.class, pm);
            rv = new Timestamp(System.currentTimeMillis());
            
        } else if (java.sql.Date.class.equals(type)) {
            path = new PDate<java.sql.Date>(java.sql.Date.class, pm);
            rv = new java.sql.Date(System.currentTimeMillis());
            
        } else if (java.sql.Time.class.equals(type)) {
            path = new PTime<java.sql.Time>(java.sql.Time.class, pm);
            rv = new java.sql.Time(System.currentTimeMillis());

        } else if (Long.class.equals(type) || long.class.equals(type)) {
            path = new PNumber<Long>(Long.class, pm);
            rv = Long.valueOf(42l);

        } else if (Short.class.equals(type) || short.class.equals(type)) {
            path = new PNumber<Short>(Short.class, pm);
            rv = Short.valueOf((short) 42);

        } else if (Double.class.equals(type) || double.class.equals(type)) {
            path = new PNumber<Double>(Double.class, pm);
            rv = Double.valueOf(42d);

        } else if (Float.class.equals(type) || float.class.equals(type)) {
            path = new PNumber<Float>(Float.class, pm);
            rv = Float.valueOf(42f);

        } else if (BigInteger.class.equals(type)) {
            path = new PNumber<BigInteger>(BigInteger.class, pm);
            rv = BigInteger.valueOf(42l);

        } else if (BigDecimal.class.equals(type)) {
            path = new PNumber<BigDecimal>(BigDecimal.class, pm);
            rv = BigDecimal.valueOf(42d);

        } else if (Boolean.class.equals(type) || boolean.class.equals(type)) {
            path = new PBoolean(pm);
            rv = Boolean.TRUE;

        } else if (List.class.isAssignableFrom(type)) {
            final Class<Object> elementType = (Class)getTypeParameter(genericType, 0);
            path = new PList<Object,PEntity<Object>>(elementType, (Class)PEntity.class, pm){
                @Override
                public PEntity get(Expr<Integer> index) {
                    return new PEntity(elementType, forListAccess(index));
                }
                @Override
                public PEntity get(int index) {
                    return new PEntity(elementType, forListAccess(index));
                }
            };
            rv = aliasFactory.createAliasForProperty(type, parent, path);

        } else if (Set.class.isAssignableFrom(type)) {
            Class<?> elementType = getTypeParameter(genericType, 0);
            path = new PSet(elementType, elementType.getName(), pm);
            rv = aliasFactory.createAliasForProperty(type, parent, path);

        } else if (Collection.class.isAssignableFrom(type)) {
            Class<?> elementType = getTypeParameter(genericType, 0);
            path = new PCollection(elementType, elementType.getSimpleName(), pm);
            rv = aliasFactory.createAliasForProperty(type, parent, path);

        } else if (Map.class.isAssignableFrom(type)) {
            Class<Object> keyType = (Class)getTypeParameter(genericType, 0);
            final Class<Object> valueType = (Class)getTypeParameter(genericType, 1);
            path = new PMap<Object,Object,PEntity<Object>>(keyType, valueType, (Class)PEntity.class, pm){
                @Override
                public PEntity get(Expr<Object> key) {
                    return new PEntity(valueType, forMapAccess(key));
                }
                @Override
                public PEntity get(Object key) {
                    return new PEntity(valueType, forMapAccess(key));
                }
            };
            rv = aliasFactory.createAliasForProperty(type, parent, path);

        } else if (Enum.class.isAssignableFrom(type)) {
            path = new PSimple<T>(type, pm);
            rv = type.getEnumConstants()[0];
            
        } else {
            if (Comparable.class.isAssignableFrom(type)){
                path = new PComparable(type, pm);
            }else{
                path = new PEntity<T>((Class<T>) type, pm);    
            }                        
            if (!Modifier.isFinal(type.getModifiers())){
                rv = aliasFactory.createAliasForProperty(type, parent, path);    
            }else{
                rv = null;
            }
        }
        propToObj.put(propKey, rv);
        propToExpr.put(propKey, path);
        return (T) rv;
    }

    private String propertyNameForGetter(Method method) {
        String name = method.getName();
        name = name.startsWith("is") ? name.substring(2) : name.substring(3);
        return StringUtils.uncapitalize(name);
    }

}
