/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.reindex.remote;

import org.elasticsearch.Version;
import org.elasticsearch.action.bulk.byscroll.ScrollableHitSource.BasicHit;
import org.elasticsearch.action.bulk.byscroll.ScrollableHitSource.Hit;
import org.elasticsearch.action.bulk.byscroll.ScrollableHitSource.Response;
import org.elasticsearch.action.bulk.byscroll.ScrollableHitSource.SearchFailure;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ObjectParser.ValueType;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentLocation;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;

import java.io.IOException;
import java.util.List;
import java.util.function.BiFunction;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.constructorArg;
import static org.elasticsearch.common.xcontent.ConstructingObjectParser.optionalConstructorArg;

/**
 * Parsers to convert the response from the remote host into objects useful for {@link RemoteScrollableHitSource}.
 */
final class RemoteResponseParsers {
    private RemoteResponseParsers() {}

    /**
     * Parser for an individual {@code hit} element.
     */
    public static final ConstructingObjectParser<BasicHit, Void> HIT_PARSER =
            new ConstructingObjectParser<>("hit", true, a -> {
                int i = 0;
                String index = (String) a[i++];
                String type = (String) a[i++];
                String id = (String) a[i++];
                Long version = (Long) a[i++];
                return new BasicHit(index, type, id, version == null ? -1 : version);
            });
    static {
        HIT_PARSER.declareString(constructorArg(), new ParseField("_index"));
        HIT_PARSER.declareString(constructorArg(), new ParseField("_type"));
        HIT_PARSER.declareString(constructorArg(), new ParseField("_id"));
        HIT_PARSER.declareLong(optionalConstructorArg(), new ParseField("_version"));
        HIT_PARSER.declareObject(BasicHit::setSource, (p, s) -> {
            try {
                /*
                 * We spool the data from the remote back into xcontent so we can get bytes to send. There ought to be a better way but for
                 * now this should do.
                 */
                try (XContentBuilder b = JsonXContent.contentBuilder()) {
                    b.copyCurrentStructure(p);
                    return b.bytes();
                }
            } catch (IOException e) {
                throw new ParsingException(p.getTokenLocation(), "[hit] failed to parse [_source]", e);
            }
        }, new ParseField("_source"));
        ParseField routingField = new ParseField("_routing");
        ParseField parentField = new ParseField("_parent");
        ParseField ttlField = new ParseField("_ttl");
        HIT_PARSER.declareString(BasicHit::setRouting, routingField);
        HIT_PARSER.declareString(BasicHit::setParent, parentField);
        // Pre-2.0.0 parent and routing come back in "fields"
        class Fields {
            String routing;
            String parent;
        }
        ObjectParser<Fields, Void> fieldsParser = new ObjectParser<>("fields", Fields::new);
        HIT_PARSER.declareObject((hit, fields) -> {
            hit.setRouting(fields.routing);
            hit.setParent(fields.parent);
        }, fieldsParser, new ParseField("fields"));
        fieldsParser.declareString((fields, routing) -> fields.routing = routing, routingField);
        fieldsParser.declareString((fields, parent) -> fields.parent = parent, parentField);
        fieldsParser.declareLong((fields, ttl) -> {}, ttlField); // ignore ttls since they have been removed
    }

    /**
     * Parser for the {@code hits} element. Parsed to an array of {@code [total (Long), hits (List<Hit>)]}.
     */
    public static final ConstructingObjectParser<Object[], Void> HITS_PARSER =
            new ConstructingObjectParser<>("hits", true, a -> a);
    static {
        HITS_PARSER.declareLong(constructorArg(), new ParseField("total"));
        HITS_PARSER.declareObjectArray(constructorArg(), HIT_PARSER, new ParseField("hits"));
    }

    /**
     * Parser for {@code failed} shards in the {@code _shards} elements.
     */
    public static final ConstructingObjectParser<SearchFailure, Void> SEARCH_FAILURE_PARSER =
            new ConstructingObjectParser<>("failure", true, a -> {
                int i = 0;
                String index = (String) a[i++];
                Integer shardId = (Integer) a[i++];
                String nodeId = (String) a[i++];
                Object reason = a[i++];

                Throwable reasonThrowable;
                if (reason instanceof String) {
                    reasonThrowable = new RuntimeException("Unknown remote exception with reason=[" + (String) reason + "]");
                } else {
                    reasonThrowable = (Throwable) reason;
                }
                return new SearchFailure(reasonThrowable, index, shardId, nodeId);
            });
    static {
        SEARCH_FAILURE_PARSER.declareString(optionalConstructorArg(), new ParseField("index"));
        SEARCH_FAILURE_PARSER.declareInt(optionalConstructorArg(), new ParseField("shard"));
        SEARCH_FAILURE_PARSER.declareString(optionalConstructorArg(), new ParseField("node"));
        SEARCH_FAILURE_PARSER.declareField(constructorArg(), (p, c) -> {
            if (p.currentToken() == XContentParser.Token.START_OBJECT) {
                return ThrowableBuilder.PARSER.apply(p, c);
            } else {
                return p.text();
            }
        }, new ParseField("reason"), ValueType.OBJECT_OR_STRING);
    }

    /**
     * Parser for the {@code _shards} element. Throws everything out except the errors array if there is one. If there isn't one then it
     * parses to an empty list.
     */
    public static final ConstructingObjectParser<List<Throwable>, Void> SHARDS_PARSER =
            new ConstructingObjectParser<>("_shards", true, a -> {
                @SuppressWarnings("unchecked")
                List<Throwable> failures = (List<Throwable>) a[0];
                failures = failures == null ? emptyList() : failures;
                return failures;
            });
    static {
        SHARDS_PARSER.declareObjectArray(optionalConstructorArg(), SEARCH_FAILURE_PARSER, new ParseField("failures"));
    }

    public static final ConstructingObjectParser<Response, Void> RESPONSE_PARSER =
            new ConstructingObjectParser<>("search_response", true, a -> {
                int i = 0;
                Throwable catastrophicFailure = (Throwable) a[i++];
                if (catastrophicFailure != null) {
                    return new Response(false, singletonList(new SearchFailure(catastrophicFailure)), 0, emptyList(), null);
                }
                boolean timedOut = (boolean) a[i++];
                String scroll = (String) a[i++];
                Object[] hitsElement = (Object[]) a[i++];
                @SuppressWarnings("unchecked")
                List<SearchFailure> failures = (List<SearchFailure>) a[i++];

                long totalHits = 0;
                List<Hit> hits = emptyList();

                // Pull apart the hits element if we got it
                if (hitsElement != null) {
                    i = 0;
                    totalHits = (long) hitsElement[i++];
                    @SuppressWarnings("unchecked")
                    List<Hit> h = (List<Hit>) hitsElement[i++];
                    hits = h;
                }

                return new Response(timedOut, failures, totalHits, hits, scroll);
            });
    static {
        RESPONSE_PARSER.declareObject(optionalConstructorArg(), ThrowableBuilder.PARSER::apply, new ParseField("error"));
        RESPONSE_PARSER.declareBoolean(optionalConstructorArg(), new ParseField("timed_out"));
        RESPONSE_PARSER.declareString(optionalConstructorArg(), new ParseField("_scroll_id"));
        RESPONSE_PARSER.declareObject(optionalConstructorArg(), HITS_PARSER, new ParseField("hits"));
        RESPONSE_PARSER.declareObject(optionalConstructorArg(), SHARDS_PARSER, new ParseField("_shards"));
    }

    /**
     * Collects stuff about Throwables and attempts to rebuild them.
     */
    public static class ThrowableBuilder {
        public static final BiFunction<XContentParser, Void, Throwable> PARSER;
        static {
            ObjectParser<ThrowableBuilder, Void> parser = new ObjectParser<>("reason", true, ThrowableBuilder::new);
            PARSER = parser.andThen(ThrowableBuilder::build);
            parser.declareString(ThrowableBuilder::setType, new ParseField("type"));
            parser.declareString(ThrowableBuilder::setReason, new ParseField("reason"));
            parser.declareObject(ThrowableBuilder::setCausedBy, PARSER::apply, new ParseField("caused_by"));

            // So we can give a nice error for parsing exceptions
            parser.declareInt(ThrowableBuilder::setLine, new ParseField("line"));
            parser.declareInt(ThrowableBuilder::setColumn, new ParseField("col"));
        }

        private String type;
        private String reason;
        private Integer line;
        private Integer column;
        private Throwable causedBy;

        public Throwable build() {
            Throwable t = buildWithoutCause();
            if (causedBy != null) { 
                t.initCause(causedBy);
            }
            return t;
        }

        private Throwable buildWithoutCause() {
            requireNonNull(type, "[type] is required");
            requireNonNull(reason, "[reason] is required");
            switch (type) {
            // Make some effort to use the right exceptions
            case "es_rejected_execution_exception":
                return new EsRejectedExecutionException(reason);
            case "parsing_exception":
                XContentLocation location = null;
                if (line != null && column != null) {
                    location = new XContentLocation(line, column);
                }
                return new ParsingException(location, reason);
            // But it isn't worth trying to get it perfect....
            default:
                return new RuntimeException(type + ": " + reason);
            }
        }

        public void setType(String type) {
            this.type = type;
        }
        public void setReason(String reason) {
            this.reason = reason;
        }
        public void setLine(Integer line) {
            this.line = line;
        }
        public void setColumn(Integer column) {
            this.column = column;
        }
        public void setCausedBy(Throwable causedBy) {
            this.causedBy = causedBy;
        }
    }

    /**
     * Parses the main action to return just the {@linkplain Version} that it returns. We throw everything else out.
     */
    public static final ConstructingObjectParser<Version, Void> MAIN_ACTION_PARSER = new ConstructingObjectParser<>(
            "/", true, a -> (Version) a[0]);
    static {
        ConstructingObjectParser<Version, Void> versionParser = new ConstructingObjectParser<>(
                "version", true, a -> Version.fromString((String) a[0]));
        versionParser.declareString(constructorArg(), new ParseField("number"));
        MAIN_ACTION_PARSER.declareObject(constructorArg(), versionParser, new ParseField("version"));
    }
}
