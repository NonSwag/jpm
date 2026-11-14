package demo;
public class Core {
    @lombok.Value public static class P { String s; }
    public static String hi() {
        return com.google.common.base.Joiner.on(",").join(org.apache.commons.lang3.StringUtils.reverse("eroc"), new P("lombok").getS(),
            new com.fasterxml.jackson.databind.ObjectMapper().getClass().getSimpleName());
    }
}
