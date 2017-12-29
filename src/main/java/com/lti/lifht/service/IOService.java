package com.lti.lifht.service;

import static com.lti.lifht.constant.ExcelConstant.ALC_MAP;
import static com.lti.lifht.constant.ExcelConstant.HC_MAP;
import static com.lti.lifht.constant.ExcelConstant.SWP_MAP;
import static com.lti.lifht.util.CommonUtil.getNext;
import static com.lti.lifht.util.CommonUtil.getPrev;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.lti.lifht.constant.CommonConstant;
import com.lti.lifht.entity.EntryDate;
import com.lti.lifht.entity.EntryPair;
import com.lti.lifht.model.EmployeeBean;
import com.lti.lifht.model.EntryDateBean;
import com.lti.lifht.model.EntryPairBean;
import com.lti.lifht.model.EntryRange;
import com.lti.lifht.model.EntryRaw;
import com.lti.lifht.repository.EmployeeRepository;
import com.lti.lifht.repository.EntryDateRepository;
import com.lti.lifht.repository.EntryPairRepository;

import one.util.streamex.StreamEx;

@Service
public class IOService {

	@Autowired
	EmployeeRepository employeeRepo;

	@Autowired
	EntryPairRepository entryPairRepo;

	@Autowired
	EntryDateRepository entryDateRepo;

	public Integer saveOrUpdateRawEntry(List<Map<String, String>> entries) {

		Comparator<EntryRaw> byPsNumDateTime = Comparator.comparing(EntryRaw::getPsNumber)
				.thenComparing(EntryRaw::getSwipeDate)
				.thenComparing(EntryRaw::getSwipeTime);

		Predicate<Map<String, String>> validEvent = entry -> {
			String eventNumber = entry.get(SWP_MAP.get("eventNumber"));
			eventNumber = eventNumber != null ? eventNumber : "201";
			return !(eventNumber.equals("200") || eventNumber.equals("215") || eventNumber.startsWith("---"));
		};

		// parse row to EntryRaw
		List<EntryRaw> entryList = entries.stream()
				.filter(Objects::nonNull)
				.filter(validEvent)
				.filter(entry -> entry.get(SWP_MAP.get("swipeDoor"))
						.contains("Apple Main Door"))
				.map(EntryRaw::new)
				.sorted(byPsNumDateTime)
				.collect(Collectors.toList());

		int entrySize = entryList.size();

		List<EntryRaw> filteredList = new ArrayList<>();

		Predicate<Integer> doorNotNull = index -> null != entryList.get(index)
				&& null != entryList.get(index).getSwipeDoor();

		BiPredicate<EntryRaw, EntryRaw> validRow = (current, adjacent) -> {
			return current.getPsNumber().equals(adjacent.getPsNumber())
					&& current.getSwipeDate().equals(adjacent.getSwipeDate());
		};

		BiPredicate<EntryRaw, EntryRaw> doorPair = (current, next) -> {
			return current.getSwipeDoor().endsWith(CommonConstant.ENTRY) &&
					next.getSwipeDoor().endsWith(CommonConstant.EXIT);
		};

		BiPredicate<EntryRaw, EntryRaw> sameDoor = (current, adjacent) -> {
			return current.getSwipeDoor().equals(adjacent.getSwipeDoor());
		};

		BiPredicate<EntryRaw, EntryRaw> timeNotNull = (current, adjacent) -> {
			return null != current
					&& null != current.getSwipeTime()
					&& null != adjacent
					&& null != adjacent.getSwipeTime();
		};

		// filter repeating doors
		Predicate<Integer> duplicateEntry = index -> {
			EntryRaw current = entryList.get(index);

			if (index > 0) { // filter duplicate door entries
				EntryRaw previous = getPrev.apply(entryList, index);
				return validRow.test(current, previous)
						? !sameDoor.test(current, previous)
						: true;

			} else if (index == 0 && index + 1 < entrySize) { // validate first pair
				EntryRaw next = getNext.apply(entryList, index);

				return validRow.test(current, next)
						? doorPair.test(current, next)
						: false;
			}
			return true;
		};

		Consumer<Integer> addToFilteredList = index -> filteredList.add(entryList.get(index));

		// parse EntryRaw to EntryPair
		BiFunction<EntryRaw, EntryRaw, EntryPairBean> toEntryPair = (current, next) -> {
			if (timeNotNull.test(current, next)
					&& validRow.test(current, next)
					&& doorPair.test(current, next)) {

				EntryPairBean pair = new EntryPairBean(current);
				pair.setSwipeIn(current.getSwipeTime());
				pair.setSwipeOut(next.getSwipeTime());
				return pair;
			}
			return null;
		};

		// filter duplicates
		IntStream.range(0, entrySize)
				.boxed()
				.filter(doorNotNull)
				.filter(duplicateEntry)
				.forEach(addToFilteredList);

		// map to pairs
		List<EntryPairBean> pairList = StreamEx.of(filteredList)
				.sorted(byPsNumDateTime)
				.nonNull()
				.pairMap(toEntryPair)
				.nonNull()
				.collect(Collectors.toList());

		entryPairRepo.saveOrUpdatePair(pairList
				.stream()
				.map(EntryPair::new)
				.filter(pair -> NumberUtils.isCreatable(pair.getPsNumber()))
				.collect(Collectors.toList()));

		return saveOrUpdateEntryDate();
	}

	public Integer saveOrUpdateEntryDate() {

		List<EntryPair> entityList = entryPairRepo.findAll();

		List<EntryPairBean> pairList = entityList
				.stream()
				.map(EntryPairBean::new)
				.collect(Collectors.toList());

		List<EntryDateBean> entryDateList = new ArrayList<>();

		pairList.stream()
				.collect(Collectors.groupingBy(EntryPairBean::getSwipeDate))
				.forEach((date, psList) -> {

					psList.stream()
							.filter(entry -> null != entry.getPsNumber())
							.collect(Collectors.groupingBy(EntryPairBean::getPsNumber))
							.forEach((psNumber, groupedList) -> {

								LocalTime firstIn = groupedList.stream()
										.findFirst()
										.get()
										.getSwipeIn();

								LocalTime lastOut = groupedList.stream()
										.reduce((current, next) -> next)
										.get()
										.getSwipeOut();

								String door = groupedList.stream()
										.map(EntryPairBean::getSwipeDoor)
										.findAny()
										.orElse("Invalid");

								Duration durationSum = groupedList.stream()
										.map(EntryPairBean::getDuration)
										.reduce(Duration::plus)
										.orElse(Duration.ofMillis(0));

								entryDateList
										.add(new EntryDateBean(psNumber, date, door, durationSum, firstIn, lastOut));
							});
				});

		return entryDateRepo.saveOrUpdateDate(entryDateList
				.stream()
				.map(EntryDate::new)
				.collect(Collectors.toList()));
	}

	public Integer saveOrUpdateHeadCount(List<Map<String, String>> rows) {

		List<EmployeeBean> offshoreList = rows
				.stream()
				.filter(row -> row.get(HC_MAP.get("offshore")).equalsIgnoreCase("Yes"))
				.filter(row -> StringUtils.isNotBlank(row.get(HC_MAP.get("psNumber"))))
				.map(row -> new EmployeeBean(row, HC_MAP))
				.collect(Collectors.toList());

		return employeeRepo.saveOrUpdateHeadCount(offshoreList);
	}

	public Integer saveOrUpdateProjectAllocation(List<Map<String, String>> rows) {

		List<String> psNumberList = employeeRepo.findAll()
				.stream()
				.map(EmployeeBean::new)
				.map(EmployeeBean::getPsNumber)
				.collect(Collectors.toList());

		rows = rows
				.stream()
				.filter(row -> psNumberList.contains(row.get(ALC_MAP.get("psNumber"))))
				.collect(Collectors.toList());

		List<EmployeeBean> employeeList = rows
				.stream()
				.map(row -> new EmployeeBean(row, ALC_MAP))
				.collect(Collectors.toList());

		return employeeRepo.saveOrUpdateProjectAllocation(employeeList);
	}

	public Workbook generateRangeMultiReport(List<EntryRange> entries, String[] reportHeaders) {
		try {
			return createTable(entries.toArray(), reportHeaders);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	public Workbook createTable(Object[] rowArr, String[] reportHeaders) throws FileNotFoundException, IOException {
		Workbook wb = new XSSFWorkbook();
		XSSFSheet sheet = (XSSFSheet) wb.createSheet();

		int rowLength = rowArr.length;

		// set headers
		int colLength = reportHeaders.length;
		XSSFRow headerRow = sheet.createRow(0); // first row as column names

		IntStream.range(0, colLength).forEach(colIndex -> {
			XSSFCell cell = headerRow.createCell(colIndex);
			cell.setCellValue(reportHeaders[colIndex]);
		});

		// set row, column values
		IntStream.range(0, rowLength).forEach(rowIndex -> {
			String[] colArr = rowArr[rowIndex].toString().split(",");
			XSSFRow row = sheet.createRow(rowIndex + 1); // +1 as first row for headers

			IntStream.range(0, colLength).forEach(colIndex -> {
				XSSFCell cell = row.createCell(colIndex);
				cell.setCellValue(colArr[colIndex]);
				sheet.autoSizeColumn(colIndex);
			});
		});

		return wb;
	}

}
