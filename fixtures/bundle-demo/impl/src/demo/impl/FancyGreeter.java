package demo.impl;

public class FancyGreeter implements demo.api.Greeter {
    public String greet(String who) {
        return org.apache.commons.text.WordUtils.capitalizeFully("hello " + who);
    }
}
