package io.camunda.blueberry.api;

import io.camunda.blueberry.connect.*;
import io.camunda.blueberry.exception.OperationException;
import io.camunda.blueberry.operation.checkdata.CheckDataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;


@RestController
@RequestMapping("blueberry")

public class CheckDataController {

private final CheckDataManager checkDataManager;

    Logger logger = LoggerFactory.getLogger(io.camunda.blueberry.api.DashboardRestController.class);

    public CheckDataController(
            CheckDataManager checkDataManager) {
        this.checkDataManager = checkDataManager;
    }

    @GetMapping(value = "/api/checkdata", produces = "application/json")
    public Map<String, Object> checkData() {
        try {
            logger.debug("Rest [/api/checkdata");
            return checkDataManager.checkData().toMap();
        } catch (OperationException oe) {
            Map<String,Object> result = new HashMap<>();
            result.put("errorMessage", oe.getMessage());
            result.put("error", oe.getError());
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
