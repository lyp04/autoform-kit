package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MaterialRefreshRulesTest {
    private static JSONObject adapterFixture() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path path : new Path[]{
                cwd.resolve("panel/backend-adapter.example.json"),
                cwd.resolve("../panel/backend-adapter.example.json")}) {
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(Files.readAllBytes(path),
                    StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("shared panel fixture not found from " + cwd);
    }

    private static BackendAdapter adapter() throws Exception {
        return BackendAdapter.from(new JSONObject().put("backendAdapter", adapterFixture()));
    }

    private static BackendAdapter adapterWithExistingQuantityPolicy(String policy)
            throws Exception {
        JSONObject fixture = adapterFixture();
        fixture.getJSONObject("operations").getJSONObject("templateDetail")
            .put("existingQuantityPolicy", policy);
        return BackendAdapter.from(new JSONObject().put("backendAdapter", fixture));
    }

    private static JSONObject profile() throws Exception {
        return new JSONObject().put("materialGroups", new JSONArray()
            .put(new JSONObject()
                .put("field", "items-main")
                .put("title", "Panel-owned title")
                .put("selectAll", true)
                .put("materials", new JSONArray()
                    .put(new JSONObject()
                        .put("code", "EXAMPLE-A")
                        .put("name", "Existing A")
                        .put("defaultQty", 2)))));
    }

    private static JSONObject option(String code, String label, Object quantity)
            throws Exception {
        JSONObject out = new JSONObject().put("value", code).put("label", label)
            .put("englishLabel", label + " EN");
        if (quantity != null) out.put("quantity", quantity);
        return out;
    }

    private static JSONObject itemField(String field, JSONArray options) throws Exception {
        return new JSONObject()
            .put("id", field)
            .put("type", "items")
            .put("parentType", "")
            .put("typeName", "")
            .put("title", "Live title")
            .put("englishTitle", "Live title")
            .put("options", options);
    }

    @Test
    public void refreshesOnlyPanelDeclaredGroupsAndAdmitsNewItemsWithExplicitQuantity()
            throws Exception {
        JSONObject data = new JSONObject().put("fields", new JSONArray()
            .put(itemField("items-main", new JSONArray()
                .put(option("EXAMPLE-A", "Live A", null))
                .put(option("EXAMPLE-B", "Live B", 3))))
            .put(itemField("items-unknown", new JSONArray()
                .put(option("EXAMPLE-UNKNOWN", "Unknown", 1)))));

        JSONArray groups = MaterialRefreshRules.refreshedGroups(
            profile(), data, adapter().materialRefresh);

        assertEquals(1, groups.length());
        JSONObject group = groups.getJSONObject(0);
        assertEquals("items-main", group.getString("field"));
        assertEquals("Panel-owned title", group.getString("title"));
        assertEquals(2, group.getJSONArray("materials").length());
        assertEquals(2, group.getJSONArray("materials").getJSONObject(0)
            .getInt("defaultQty"));
        assertEquals(3, group.getJSONArray("materials").getJSONObject(1)
            .getInt("defaultQty"));
    }

    @Test
    public void missingOrEmptyConfiguredGroupFailsClosed() throws Exception {
        BackendAdapter.MaterialRefresh mapping = adapter().materialRefresh;
        assertRejected(profile(), new JSONObject().put("fields", new JSONArray()),
            mapping, "missing refreshed material group");
        assertRejected(profile(), new JSONObject().put("fields", new JSONArray()
            .put(itemField("items-main", new JSONArray()))), mapping,
            "empty refreshed material group");
    }

    @Test
    public void duplicateCodesAndQuantityConflictsFailClosed() throws Exception {
        BackendAdapter.MaterialRefresh mapping = adapter().materialRefresh;
        assertRejected(profile(), new JSONObject().put("fields", new JSONArray()
            .put(itemField("items-main", new JSONArray()
                .put(option("EXAMPLE-A", "A", null))
                .put(option("EXAMPLE-A", "A duplicate", null))))), mapping,
            "duplicate or empty refreshed material code");
        assertRejected(profile(), new JSONObject().put("fields", new JSONArray()
            .put(itemField("items-main", new JSONArray()
                .put(option("EXAMPLE-A", "A", 1))))), mapping,
            "quantity conflicts with Panel profile");
    }

    @Test
    public void newItemWithoutQuantityFailsClosed() throws Exception {
        assertRejected(profile(), new JSONObject().put("fields", new JSONArray()
            .put(itemField("items-main", new JSONArray()
                .put(option("EXAMPLE-A", "A", null))
                .put(option("EXAMPLE-B", "B", null))))), adapter().materialRefresh,
            "new refreshed material has no positive quantity");
    }

    @Test
    public void explicitPanelAuthoritativePolicyKeepsConfiguredExistingQuantity()
            throws Exception {
        BackendAdapter configured = adapterWithExistingQuantityPolicy(
            BackendAdapter.MaterialRefresh.EXISTING_QUANTITY_PROFILE);
        JSONArray groups = MaterialRefreshRules.refreshedGroups(
            profile(),
            new JSONObject().put("fields", new JSONArray()
                .put(itemField("items-main", new JSONArray()
                    .put(option("EXAMPLE-A", "Live A", 9))
                    .put(option("EXAMPLE-B", "Live B", 3))))),
            configured.materialRefresh);

        assertEquals(2, groups.getJSONObject(0).getJSONArray("materials")
            .getJSONObject(0).getInt("defaultQty"));
        assertEquals(3, groups.getJSONObject(0).getJSONArray("materials")
            .getJSONObject(1).getInt("defaultQty"));
    }

    @Test
    public void panelAuthoritativePolicyStillRejectsInvalidLiveAndUnknownMissingQuantity()
            throws Exception {
        BackendAdapter.MaterialRefresh mapping = adapterWithExistingQuantityPolicy(
            BackendAdapter.MaterialRefresh.EXISTING_QUANTITY_PROFILE).materialRefresh;
        assertRejected(profile(), new JSONObject().put("fields", new JSONArray()
            .put(itemField("items-main", new JSONArray()
                .put(option("EXAMPLE-A", "A", "invalid"))))), mapping,
            "quantity must be a positive integer");
        assertRejected(profile(), new JSONObject().put("fields", new JSONArray()
            .put(itemField("items-main", new JSONArray()
                .put(option("EXAMPLE-A", "A", null))
                .put(option("EXAMPLE-B", "B", null))))), mapping,
            "new refreshed material has no positive quantity");
    }

    @Test
    public void unknownQuantityPolicyIsARefreshCapabilityError() throws Exception {
        BackendAdapter configured = adapterWithExistingQuantityPolicy("guess_from_label");
        assertTrue(configured.missingForSubmit(false, false, true, false, true)
            .contains("operations.templateDetail.existingQuantityPolicy"));
    }

    @Test
    public void refreshCapabilityIsRequiredOnlyWhenProfileEnablesIt() throws Exception {
        JSONObject fixture = adapterFixture();
        fixture.getJSONObject("fields").getJSONObject("option").remove("quantity");
        BackendAdapter missing = BackendAdapter.from(
            new JSONObject().put("backendAdapter", fixture));

        assertTrue(missing.missingForSubmit(false, false, true, false).isEmpty());
        assertTrue(missing.missingForSubmit(false, false, true, false, true)
            .contains("backendAdapter.fields.option.quantity"));
    }

    private static void assertRejected(JSONObject profile, JSONObject data,
                                       BackendAdapter.MaterialRefresh mapping,
                                       String expected) throws Exception {
        try {
            MaterialRefreshRules.refreshedGroups(profile, data, mapping);
            throw new AssertionError("refresh must fail closed");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        }
    }
}
