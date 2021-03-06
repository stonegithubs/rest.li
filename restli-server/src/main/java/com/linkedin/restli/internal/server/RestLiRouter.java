/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.restli.internal.server;


import com.linkedin.data.DataList;
import com.linkedin.data.DataMap;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.data.template.TemplateRuntimeException;
import com.linkedin.r2.filter.R2Constants;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.restli.common.ComplexKeySpec;
import com.linkedin.restli.common.ComplexResourceKey;
import com.linkedin.restli.common.CompoundKey;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.common.OperationNameGenerator;
import com.linkedin.restli.common.ProtocolVersion;
import com.linkedin.restli.common.ResourceMethod;
import com.linkedin.restli.common.RestConstants;
import com.linkedin.restli.internal.common.AllProtocolVersions;
import com.linkedin.restli.internal.common.PathSegment.PathSegmentSyntaxException;
import com.linkedin.restli.internal.server.model.ResourceMethodDescriptor;
import com.linkedin.restli.internal.server.model.ResourceModel;
import com.linkedin.restli.internal.server.util.ArgumentUtils;
import com.linkedin.restli.internal.server.util.RestLiSyntaxException;
import com.linkedin.restli.server.Key;
import com.linkedin.restli.server.ResourceLevel;
import com.linkedin.restli.server.RestLiServiceException;
import com.linkedin.restli.server.RoutingException;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Navigates the resource hierarchy to find a Resource handler for the a given URI.
 *
 * @author Josh Walker
 */
public class RestLiRouter
{
  private static final Logger log = LoggerFactory.getLogger(RestLiRouter.class);
  private static final Map<ResourceMethodMatchKey, ResourceMethod> _resourceMethodLookup = setupResourceMethodLookup();
  private final Map<String, ResourceModel> _pathRootResourceMap;

  /**
   * Constructor.
   *
   * @param pathRootResourceMap a map of resource root paths to corresponding
   *          {@link ResourceModel}s
   */
  public RestLiRouter(final Map<String, ResourceModel> pathRootResourceMap)
  {
    super();
    _pathRootResourceMap = pathRootResourceMap;
  }

  private static final Pattern SLASH_PATTERN = Pattern.compile(Pattern.quote("/"));

  /**
   * Processes provided {@link RestRequest}.
   *
   * @param req {@link RestRequest}
   * @return {@link RoutingResult}
   */
  public RoutingResult process(final RestRequest req, final RequestContext requestContext)
  {
    String path = req.getURI().getRawPath();
    if (path.length() < 2)
    {
      throw new RoutingException(HttpStatus.S_404_NOT_FOUND.getCode());
    }

    if (path.charAt(0) == '/')
    {
      path = path.substring(1);
    }

    Queue<String> remainingPath =
        new LinkedList<String>(Arrays.asList(SLASH_PATTERN.split(path)));

    String rootPath = "/" + remainingPath.poll();

    ResourceModel currentResource;
    try
    {
      currentResource =
          _pathRootResourceMap.get(URLDecoder.decode(rootPath,
                                                     RestConstants.DEFAULT_CHARSET_NAME));
    }
    catch (UnsupportedEncodingException e)
    {
      throw new RestLiInternalException("UnsupportedEncodingException while trying to decode the root path",
                                        e);
    }
    if (currentResource == null)
    {
      throw new RoutingException(String.format("No root resource defined for path '%s'",
                                               rootPath),
                                 HttpStatus.S_404_NOT_FOUND.getCode());
    }
    ServerResourceContext context;

    try
    {
      context = new ResourceContextImpl(new PathKeysImpl(), req, requestContext);
    }
    catch (RestLiSyntaxException e)
    {
      throw new RoutingException(e.getMessage(), HttpStatus.S_400_BAD_REQUEST.getCode());
    }

    return processResourceTree(currentResource, context, remainingPath);
  }

  private RoutingResult processResourceTree(final ResourceModel resource,
                                            final ServerResourceContext context,
                                            final Queue<String> remainingPath)
  {
    ResourceModel currentResource = resource;

    // iterate through all path segments, simultaneously descending the resource hierarchy
    // and parsing path keys where applicable;
    // the goal of this loop is to locate the leaf resource, which will be set in
    // currentResource, and to parse the necessary information into the context
    ResourceLevel currentLevel = currentResource.getResourceLevel();

    while (remainingPath.peek() != null)
    {
      String currentPathSegment = remainingPath.poll();

      if (currentLevel.equals(ResourceLevel.ENTITY))
      {
        currentResource =
            currentResource.getSubResource(parseSubresourceName(currentPathSegment));
        currentLevel = currentResource == null ?
            ResourceLevel.ANY : currentResource.getResourceLevel();
      }
      else
      {
        ResourceModel currentCollectionResource = currentResource;
        if (currentResource.getKeys().isEmpty())
        {
          throw new RoutingException(String.format("Path key not supported on resource '%s' for URI '%s'",
                                                   currentResource.getName(),
                                                   context.getRequestURI()),
                                     HttpStatus.S_400_BAD_REQUEST.getCode());
        }
        else if (currentResource.getKeyClass() == ComplexResourceKey.class)
        {
          parseComplexKey(currentResource, context, currentPathSegment);
          currentLevel = ResourceLevel.ENTITY;
        }
        else if (currentResource.getKeyClass() == CompoundKey.class)
        {
          CompoundKey compoundKey;
          try
          {
            compoundKey = parseCompoundKey(currentCollectionResource, context, currentPathSegment);
          }
          catch (IllegalArgumentException e)
          {
            throw new RoutingException(String.format("Malformed Compound Key: '%s'", currentPathSegment),
                                       HttpStatus.S_400_BAD_REQUEST.getCode(),
                                       e);
          }

          if (compoundKey != null
              && compoundKey.getPartKeys().containsAll(currentResource.getKeyNames()))
          {
            // full match on key parts means that we are targeting a unique entity
            currentLevel = ResourceLevel.ENTITY;
          }
        }
        else // Must be a simple key then
        {
          parseSimpleKey(currentResource, context, currentPathSegment);
          currentLevel = ResourceLevel.ENTITY;
        }
      }

      if (currentResource == null)
      {
        throw new RoutingException(HttpStatus.S_404_NOT_FOUND.getCode());
      }
    }

    parseBatchKeysParameter(currentResource, context); //now we know the key type, look for batch parameter

    return findMethodDescriptor(currentResource, currentLevel, context);
  }

  /** given path segment, parses subresource name out of it */
  private static String parseSubresourceName(final String pathSegment)
  {
    try
    {
      return URLDecoder.decode(pathSegment, RestConstants.DEFAULT_CHARSET_NAME);
    }
    catch (UnsupportedEncodingException e)
    {
      throw new RestLiInternalException("UnsupportedEncodingException while trying to decode the subresource name", e);
    }
  }

  private RoutingResult findMethodDescriptor(final ResourceModel resource,
                                             final ResourceLevel resourceLevel,
                                             final ServerResourceContext context)
  {
    ResourceMethod type = mapResourceMethod(context, resourceLevel);

    String methodName = context.getRequestActionName();
    if (methodName == null)
    {
      methodName = context.getRequestFinderName();
    }

    ResourceMethodDescriptor methodDescriptor = resource.matchMethod(type, methodName, resourceLevel);

    if (methodDescriptor != null)
    {
      context.getRawRequestContext().putLocalAttr(R2Constants.OPERATION,
                                                  OperationNameGenerator.generate(methodDescriptor.getMethodType(),
                                                                                  methodName));
      return new RoutingResult(context, methodDescriptor);
    }

    String httpMethod = context.getRequestMethod();
    if (methodName != null)
    {
      throw new RoutingException(
              String.format("%s operation " +
                            "named %s " +
                            "not supported on resource '%s' " +
                            "URI: '%s'",
                            httpMethod,
                            methodName,
                            resource.getResourceClass().getName(),
                            context.getRequestURI().toString()),
                            HttpStatus.S_400_BAD_REQUEST.getCode());
    }

    throw new RoutingException(
            String.format("%s operation not supported " +
                          "for URI: '%s' " +
                          "with " + RestConstants.HEADER_RESTLI_REQUEST_METHOD + ": '%s'",
                          httpMethod,
                          context.getRequestURI().toString(),
                          context.getRestLiRequestMethod()),
                          HttpStatus.S_400_BAD_REQUEST.getCode());
  }

  // We use a table match to ensure that we have no subtle ordering dependencies in conditional logic
  //
  // Currently only POST requests set RMETHOD header (HEADER_RESTLI_REQUEST_METHOD), however we include
  // a table entry for GET methods as well to make sure the routing doesn't fail if the client sets the header
  // when it's not necessary, as long as it doesn't conflict with the rest of the parameters.
  private static Map<ResourceMethodMatchKey, ResourceMethod> setupResourceMethodLookup()
  {
    HashMap<ResourceMethodMatchKey, ResourceMethod> result = new HashMap<ResourceMethodMatchKey, ResourceMethod>();
    //                                 METHOD    RMETHOD                    ACTION   QUERY   BATCH   ENTITY
    Object[] config =
    {
            new ResourceMethodMatchKey("GET",    "",                        false,   false,  false,  true),  ResourceMethod.GET,
            new ResourceMethodMatchKey("GET",    "",                        false,   true,   false,  false), ResourceMethod.FINDER,
            new ResourceMethodMatchKey("PUT",    "",                        false,   false,  false,  true),  ResourceMethod.UPDATE,
            new ResourceMethodMatchKey("POST",   "",                        false,   false,  false,  true),  ResourceMethod.PARTIAL_UPDATE,
            new ResourceMethodMatchKey("DELETE", "",                        false,   false,  false,  true),  ResourceMethod.DELETE,
            new ResourceMethodMatchKey("POST",   "",                        true,    false,  false,  true),  ResourceMethod.ACTION,
            new ResourceMethodMatchKey("POST",   "",                        true,    false,  false,  false), ResourceMethod.ACTION,
            new ResourceMethodMatchKey("POST",   "",                        false,   false,  false,  false), ResourceMethod.CREATE,
            new ResourceMethodMatchKey("GET",    "",                        false,   false,  false,  false), ResourceMethod.GET_ALL,

            new ResourceMethodMatchKey("GET",    "GET",                     false,   false,  false,  true),  ResourceMethod.GET,
            new ResourceMethodMatchKey("GET",    "FINDER",                  false,   true,   false,  false), ResourceMethod.FINDER,
            new ResourceMethodMatchKey("PUT",    "UPDATE",                  false,   false,  false,  true),  ResourceMethod.UPDATE,
            new ResourceMethodMatchKey("POST",   "PARTIAL_UPDATE",          false,   false,  false,  true),  ResourceMethod.PARTIAL_UPDATE,
            new ResourceMethodMatchKey("DELETE", "DELETE",                  false,   false,  false,  true),  ResourceMethod.DELETE,
            new ResourceMethodMatchKey("POST",   "ACTION",                  true,    false,  false,  true),  ResourceMethod.ACTION,
            new ResourceMethodMatchKey("POST",   "ACTION",                  true,    false,  false,  false), ResourceMethod.ACTION,
            new ResourceMethodMatchKey("POST",   "CREATE",                  false,   false,  false,  false), ResourceMethod.CREATE,
            new ResourceMethodMatchKey("GET",    "GET_ALL",                 false,   false,  false,  false), ResourceMethod.GET_ALL,

            new ResourceMethodMatchKey("GET",    "",                        false,   false,  true,   false), ResourceMethod.BATCH_GET,
            new ResourceMethodMatchKey("DELETE", "",                        false,   false,  true,   false), ResourceMethod.BATCH_DELETE,
            new ResourceMethodMatchKey("PUT",    "",                        false,   false,  true,   false), ResourceMethod.BATCH_UPDATE,
            new ResourceMethodMatchKey("POST",   "",                        false,   false,  true,   false), ResourceMethod.BATCH_PARTIAL_UPDATE,

            new ResourceMethodMatchKey("GET",    "BATCH_GET",               false,   false,  true,   false), ResourceMethod.BATCH_GET,
            new ResourceMethodMatchKey("DELETE", "BATCH_DELETE",            false,   false,  true,   false), ResourceMethod.BATCH_DELETE,
            new ResourceMethodMatchKey("PUT",    "BATCH_UPDATE",            false,   false,  true,   false), ResourceMethod.BATCH_UPDATE,
            new ResourceMethodMatchKey("POST",   "BATCH_PARTIAL_UPDATE",    false,   false,  true,   false), ResourceMethod.BATCH_PARTIAL_UPDATE,

            // batch create signature collides with non-batch create. requires RMETHOD header to distinguish
            new ResourceMethodMatchKey("POST",   "BATCH_CREATE",            false,   false,  false,  false), ResourceMethod.BATCH_CREATE
    };

    for (int ii = 0; ii < config.length; ii += 2)
    {
      ResourceMethodMatchKey key = (ResourceMethodMatchKey) config[ii];
      ResourceMethod method = (ResourceMethod) config[ii + 1];
      ResourceMethod prevValue = result.put(key, method);
      if (prevValue != null)
      {
        throw new RestLiInternalException("Routing Configuration conflict: "
            + prevValue.toString() + " conflicts with " + method.toString());
      }
    }

    return result;
  }

  private ResourceMethod mapResourceMethod(final ServerResourceContext context,
                                           final ResourceLevel resourceLevel)
  {
    ResourceMethodMatchKey key =
        new ResourceMethodMatchKey(context.getRequestMethod(),
                                   context.getRestLiRequestMethod(),
                                   context.getRequestActionName() != null,
                                   context.getRequestFinderName() != null,
                                   context.getPathKeys().getBatchIds() != null,
                                   resourceLevel.equals(ResourceLevel.ENTITY));

    if (_resourceMethodLookup.containsKey(key))
    {
      return _resourceMethodLookup.get(key);
    }

    if (context.hasParameter(RestConstants.ACTION_PARAM)
        && !"POST".equalsIgnoreCase(context.getRequestMethod()))
    {
      throw new RoutingException(
              String.format("All action methods (specified via '%s' in URI) must " +
                            "be submitted as a POST (was %s)",
                            RestConstants.ACTION_PARAM,
                            context.getRequestMethod()),
                            HttpStatus.S_400_BAD_REQUEST.getCode());

    }

    throw new RoutingException(String.format("Method '%s' is not supported for URI '%s'",
                                             context.getRequestMethod(),
                                             context.getRequestURI()),
                               HttpStatus.S_400_BAD_REQUEST.getCode());

  }

  private static CompoundKey parseCompoundKey(final ResourceModel resource,
                                              final ServerResourceContext context,
                                              final String pathSegment)
 {
   CompoundKey compoundKey;
   try
   {
     compoundKey =
       ArgumentUtils.parseCompoundKey(pathSegment, resource.getKeys(),
                                      context.getRestliProtocolVersion());
   }
   catch (PathSegmentSyntaxException e)
   {
     throw new RoutingException(String.format("input %s is not a Compound key", pathSegment),
                                HttpStatus.S_400_BAD_REQUEST.getCode(),
                                e);
   }
   catch (IllegalArgumentException e)
   {
     throw new RoutingException(String.format("input %s is not a Compound key", pathSegment),
                                HttpStatus.S_400_BAD_REQUEST.getCode(),
                                e);
   }


    for (String simpleKeyName : compoundKey.getPartKeys())
    {
      context.getPathKeys().append(simpleKeyName, compoundKey.getPart(simpleKeyName));
    }
    context.getPathKeys().append(resource.getKeyName(), compoundKey);
    return compoundKey;
  }


  /**
   * Instantiate the complex key from the current path segment (treat is as a list of
   * query parameters) and put it into the context.
   *
   * @param currentPathSegment
   * @param context
   * @param resource
   * @return
   */
  private static void parseComplexKey(final ResourceModel resource,
                                      final ServerResourceContext context,
                                      final String currentPathSegment)
  {
    try
    {
      ComplexKeySpec<? extends RecordTemplate, ? extends RecordTemplate> complexKeyType =
          ComplexKeySpec.forClassesMaybeNull( resource.getKeyKeyClass(), resource.getKeyParamsClass());
      ComplexResourceKey<RecordTemplate, RecordTemplate> complexKey =
          ComplexResourceKey.parseString(currentPathSegment, complexKeyType, context.getRestliProtocolVersion());

      context.getPathKeys().append(resource.getKeyName(), complexKey);
    }
    catch (PathSegmentSyntaxException e)
    {
      throw new RoutingException(String.format("Complex key query parameters parsing error: '%s'",
                                               e.getMessage()),
                                 HttpStatus.S_400_BAD_REQUEST.getCode());
    }
  }

  private void parseBatchKeysParameter(final ResourceModel resource,
                                       final ServerResourceContext context)
  {
    Class<?> keyClass = resource.getKeyClass();
    ProtocolVersion version = context.getRestliProtocolVersion();
    final Set<Object> batchKeys;

    if (ComplexResourceKey.class.equals(keyClass))
    {
      // Parse all query parameters into a data map.
      DataMap allParametersDataMap = context.getParameters();

      // Get the batch request keys from the IDS list at the root of the map.
      DataList batchIds = allParametersDataMap.getDataList(RestConstants.QUERY_BATCH_IDS_PARAM);
      if (batchIds == null)
      {
        batchKeys = null;
      }
      else if (batchIds.isEmpty())
      {
        batchKeys = Collections.emptySet();
      }
      else
      {
        batchKeys = new HashSet<Object>();

        // Validate the complex keys and put them into the context batch keys
        for (Object complexKey : batchIds)
        {
          if (!(complexKey instanceof DataMap))
          {
            log.warn("Invalid structure of key '" + complexKey.toString() + "', skipping key.");
            context.getBatchKeyErrors().put(complexKey, new RestLiServiceException(HttpStatus.S_400_BAD_REQUEST));
            continue;
          }
          batchKeys.add(ComplexResourceKey.buildFromDataMap((DataMap) complexKey, ComplexKeySpec.forClassesMaybeNull(resource.getKeyKeyClass(), resource.getKeyParamsClass())));
        }
      }
    }
    else if (CompoundKey.class.equals(keyClass)
      && version.compareTo(AllProtocolVersions.RESTLI_PROTOCOL_2_0_0.getProtocolVersion()) >= 0)
    {
      DataMap allParametersDataMap = context.getParameters();

      // Get the batch request keys from the IDS list at the root of the map.
      DataList batchIds = allParametersDataMap.getDataList(RestConstants.QUERY_BATCH_IDS_PARAM);
      if (batchIds == null)
      {
        batchKeys = null;
      }
      else if (batchIds.isEmpty())
      {
        batchKeys = Collections.emptySet();
      }
      else
      {
        batchKeys = new HashSet<Object>();

        // Validate the compound keys and put them into the contex batch keys
        for (Object compoundKey : batchIds)
        {
          if (!(compoundKey instanceof DataMap))
          {
            log.warn("Invalid structure of key '" + compoundKey.toString() + "', skipping key.");
            context.getBatchKeyErrors().put(compoundKey.toString(), new RestLiServiceException(HttpStatus.S_400_BAD_REQUEST));
            continue;
          }
          CompoundKey finalKey;
          try
          {
            finalKey = ArgumentUtils.dataMapToCompoundKey((DataMap) compoundKey, resource.getKeys());
          }
          catch (IllegalArgumentException e)
          {
            log.warn("Invalid structure of key '" + compoundKey.toString() + "', skipping key.");
            context.getBatchKeyErrors().put(compoundKey.toString(), new RestLiServiceException(HttpStatus.S_400_BAD_REQUEST));
            continue;
          }
          batchKeys.add(finalKey);
        }
      }
    }
    // collection batch get in v2, collection or association batch get in v1
    else if (context.hasParameter(RestConstants.QUERY_BATCH_IDS_PARAM))
    {
      batchKeys = new HashSet<Object>();

      List<String> ids = context.getParameterValues(RestConstants.QUERY_BATCH_IDS_PARAM);
      if (version.compareTo(AllProtocolVersions.RESTLI_PROTOCOL_2_0_0.getProtocolVersion()) >= 0)
      {
        for (String id: ids)
        {
          Key key = resource.getPrimaryKey();
          Object value;
          // in v2, compound keys have already been converted and dealt with, so all we need to do here is convert simple values.
          value = ArgumentUtils.convertSimpleValue(id, key.getDataSchema(), key.getType());
          batchKeys.add(value);
        }
      }
      else
      {
        for (String id: ids)
        {
          try
          {
            // in v1, compound keys have not been fully parsed or dealt with yet, so we need to take them into account.
            Object value = parseKeyFromBatchV1(id, resource);
            batchKeys.add(value);
          }
          catch (NumberFormatException e)
          {
            log.warn("Caught NumberFormatException parsing batch key '" + id + "', skipping key.");
            context.getBatchKeyErrors().put(id, new RestLiServiceException(HttpStatus.S_400_BAD_REQUEST, null, e));
          }
          catch (IllegalArgumentException e)
          {
            log.warn("Caught IllegalArgumentException parsing batch key '" + id + "', skipping key.");
            context.getBatchKeyErrors().put(id, new RestLiServiceException(HttpStatus.S_400_BAD_REQUEST, null, e));
          }
          catch (PathSegmentSyntaxException e)
          {
            log.warn("Caught IllegalArgumentException parsing batch key '" + id + "', skipping key.");
            context.getBatchKeyErrors().put(id,
                                            new RestLiServiceException(HttpStatus.S_400_BAD_REQUEST,
                                                                       null, e));
          }
        }
      }
    }
    else
    {
      batchKeys = null;
    }

    context.getPathKeys().setBatchKeys(batchKeys);
  }

  private static void parseSimpleKey(final ResourceModel resource,
                                     final ServerResourceContext context,
                                     final String pathSegment)
  {
    Object parsedKey;
    try
    {
      parsedKey = ArgumentUtils.parseSimplePathKey(pathSegment, resource, context.getRestliProtocolVersion());
    }
    catch (NumberFormatException e)
    {
      // thrown from Integer.valueOf or Long.valueOf
      throw new RoutingException(String.format("Key value '%s' must be of type '%s'",
                                                pathSegment,
                                                resource.getKeyClass().getName()),
                                 HttpStatus.S_400_BAD_REQUEST.getCode(),
                                 e);
    }
    catch (IllegalArgumentException e)
    {
      // thrown from Enum.valueOf
      throw new RoutingException(String.format("Key parameter value '%s' is invalid", pathSegment),
                                 HttpStatus.S_400_BAD_REQUEST.getCode(),
                                 e);
    }
    catch (TemplateRuntimeException e)
    {
      // thrown from DateTemplateUtil.coerceOutput
      throw new RoutingException(String.format("Key parameter value '%s' is invalid", pathSegment),
                                HttpStatus.S_400_BAD_REQUEST.getCode(),
                                e);
    }
    context.getPathKeys()
      .append(resource.getKeyName(), parsedKey);
  }

  private static Object parseKeyFromBatchV1(String value, ResourceModel resource)
    throws PathSegmentSyntaxException, IllegalArgumentException
  {
    ProtocolVersion version = AllProtocolVersions.RESTLI_PROTOCOL_1_0_0.getProtocolVersion();
    if (CompoundKey.class.isAssignableFrom(resource.getKeyClass()))
    {
      return ArgumentUtils.parseCompoundKey(value, resource.getKeys(), version);
    }
    else
    {
      Key key = resource.getPrimaryKey();
      return ArgumentUtils.convertSimpleValue(value, key.getDataSchema(), key.getType());
    }
  }

}
