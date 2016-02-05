package enkan.middleware;

import enkan.MiddlewareChain;
import enkan.annotation.Middleware;
import enkan.collection.MapNestedParams;
import enkan.collection.Multimap;
import enkan.collection.NestedParams;
import enkan.data.HttpRequest;
import enkan.data.HttpResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author kawasima
 */
@Middleware(name = "nestedParams", dependencies = {"params"})
public class NestedParamsMiddleware extends AbstractWebMiddleware {
    private static final Pattern RE_NESTED_NAME = Pattern.compile("^(?s)(.*?)((?:\\[.*?\\])*)$");
    private static final Pattern RE_NESTED_TOKEN = Pattern.compile("\\[(.*?)\\]");
    protected Function<String, String[]> parseNestedKeys = (paramName) -> {
        if (paramName == null) return new String[]{};

        Matcher m = RE_NESTED_NAME.matcher(paramName);
        List<String> keys = new ArrayList<>();

        if (m.find()) {
            keys.add(m.group(1));
            String ks = m.group(2);
            if (ks != null && !ks.isEmpty()) {
                Matcher mt = RE_NESTED_TOKEN.matcher(ks);
                while (mt.find()) {
                    keys.add(mt.group(1));
                }
            } else {
                return new String[]{ m.group(1) };
            }
        }

        return keys.toArray(new String[keys.size()]);
    };

    protected NestedParams assocVector(NestedParams map, String key, Object value) {
        if (!map.containsKey(key)) {
            map.put(key, new ArrayList<>());
        }
        return assocConj(map, key, value);
    }

    /**
     * Association
     *
     * <li>foo[bar]=[aaa,bbb] => {foo: {bar: [aaa, bbb]}}</li>
     * <li>for[][bar]=[aaa,bbb] => {foo: [{bar: aaa}, {bar: bbb}]}</li>
     * <li>for[]=[aaa,bbb] => {foo: [aaa, bbb]}</li>
     *
     * @param map
     * @param key
     * @param value
     * @return
     */
    protected NestedParams assocConj(NestedParams map, String key, Object value) {
        Object cur = map.get(key);
        if (cur != null) {
            if (cur instanceof List) {
                if (value instanceof List) {
                    ((List<Object>) cur).addAll((List<?>) value);
                } else {
                    ((List<Object>) cur).add(value);
                }
            } else {
                List<Object> values = new ArrayList<>();
                values.add(cur);
                if (values instanceof List) {
                    values.addAll((List<?>) value);
                } else {
                    values.add(value);
                }
            }
        } else {
            if (value instanceof List) {
                List<?> values = (List) value;
                if (values.size() > 1) {
                    assocVector(map, key, value);
                } else if (values.size() == 1) {
                    map.put(key, values.get(0));
                }
            } else {
                map.put(key, value);
            }
        }
        return map;
    }

    protected Object assocNested(NestedParams map, String[] keys, List<String> values) {
        if (keys.length > 0) {
            String[] ks = new String[keys.length - 1];

            if (ks.length > 0) {
                System.arraycopy(keys, 1, ks, 0, ks.length);

                String j = ks[0];
                if (j.isEmpty()) {
                    String[] js = new String[keys.length - 2];
                    if (js.length > 0) {
                        System.arraycopy(keys, 2, js, 0, js.length);
                    }

                    List<Object> nestedList = (List<Object>) map.getOrDefault(keys[0], new ArrayList<>(values.size()));
                    for (int i = nestedList.size(); i < values.size(); i++) nestedList.add(null);

                    for (int i = 0; i < values.size(); i++) {
                        List<String> vs = new ArrayList<>();
                        vs.add(values.get(i));
                        if (js.length > 0) {
                            nestedList.set(i,
                                    assocNested((MapNestedParams) Optional.ofNullable(nestedList.get(i)).orElse(new MapNestedParams())
                                            , js, vs));
                        } else {
                            nestedList.add(assocNested(null, js, vs));
                        }

                    }
                    map.put(keys[0], nestedList);
                    return map;
                } else {
                    MapNestedParams submap= (MapNestedParams) map.getOrDefault(keys[0], new MapNestedParams());
                    map.put(keys[0], assocNested(submap, ks, values));
                    return map;
                }
            } else {
                return assocConj(map, keys[0], values);
            }
        } else {
            return values;
        }
    }

    public HttpRequest nestedParamsRequest(HttpRequest request, Function<String, String[]> keyParser) {
        Multimap<String, String> params = Multimap.class.cast(request.getParams());
        NestedParams nestedParams = new MapNestedParams();

        params.keySet().forEach(key -> assocNested(nestedParams, keyParser.apply(key), params.getAll(key)));
        request.setParams(nestedParams);
        return request;
    }

    @Override
    public HttpResponse handle(HttpRequest request, MiddlewareChain chain) {
        return castToHttpResponse(chain.next(nestedParamsRequest(request, parseNestedKeys)));
    }
}