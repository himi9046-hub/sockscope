module sockscope {
    requires javafx.controls;
    requires com.fasterxml.jackson.databind;

    exports sockscope;
    opens sockscope to com.fasterxml.jackson.databind;
}
