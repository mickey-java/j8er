package com.lti.lifht.repository;

import static com.lti.lifht.model.ResourceDetails.RESOURCE_MAP;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.transaction.Transactional;

import org.springframework.stereotype.Repository;

import com.lti.lifht.model.EmployeeBean;
import com.lti.lifht.model.EntryDateBean;
import com.lti.lifht.model.ResourceDetails;

@Repository
@Transactional
public class EmployeeRepositoryImpl implements EmployeeRepositoryCustom {

    @PersistenceContext
    EntityManager entityManager;

    @Override
    public Integer saveOrUpdateHeadCount(List<EmployeeBean> employeeList) {

        List<Integer> updateCountList = new ArrayList<>();

        employeeList.forEach(employee -> {

            StringBuilder sql = new StringBuilder();

            sql.append("INSERT INTO employee(ps_number, ps_name, apple_manager, lti_mail, ds_id, active, billable)")
                    .append(" VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .append(" ON DUPLICATE KEY UPDATE")
                    .append(" ps_name = VALUES (ps_name),")
                    .append(" apple_manager = VALUES (apple_manager),")
                    .append(" lti_mail = VALUES (lti_mail), ")
                    .append(" ds_id = VALUES (ds_id),")
                    .append(" billable = VALUES (billable),")
                    .append(" active = VALUES (active)");

            Query insert = entityManager.createNativeQuery(sql.toString());

            insert.setParameter(1, employee.getPsNumber());
            insert.setParameter(2, employee.getPsName());
            insert.setParameter(3, employee.getManager());
            insert.setParameter(4, employee.getEmail());
            insert.setParameter(5, employee.getDsId());
            insert.setParameter(6, "Y"); // set active
            insert.setParameter(7, employee.getBillable());

            updateCountList.add(insert.executeUpdate());
        });

        return updateCountList.stream()
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    @Override
    public Integer saveOrUpdateProjectAllocation(List<EmployeeBean> employeeList) {

        List<Integer> updateCountList = new ArrayList<>();

        employeeList.forEach(employee -> {

            StringBuilder sql = new StringBuilder();

            sql.append("INSERT INTO employee(ps_number, business_unit)")
                    .append(" VALUES (?, ?)")
                    .append(" ON DUPLICATE KEY UPDATE")
                    .append(" business_unit = VALUES (business_unit)");

            Query insert = entityManager.createNativeQuery(sql.toString());
            insert.setParameter(1, employee.getPsNumber());
            insert.setParameter(2, employee.getBusinessUnit());
            updateCountList.add(insert.executeUpdate());
        });

        return updateCountList.stream()
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    @Override
    public List<ResourceDetails> getResourceDetailsList() {

        String query = "select hc.ps_number,alloc.delivery_bu,alloc.project_id,alloc.resource_location,alloc.grade,hc.ds_id,hc.imt,hc.imt1,hc.imt2,hc.ps_name,hc.po,hc.manager from allocation alloc RIGHT JOIN head_count hc ON alloc.ps_number = hc.ps_number";

        Query select = entityManager.createNativeQuery(query);

        List<Object[]> objList = select.getResultList();

        List<ResourceDetails> resourceList = objList.stream().map(ResourceDetails::new).collect(Collectors.toList());
//        objList.forEach(rs -> {
//            resourceList.add(new ResourceDetails(rs));
//        });
        
         
        return resourceList;
    }

}