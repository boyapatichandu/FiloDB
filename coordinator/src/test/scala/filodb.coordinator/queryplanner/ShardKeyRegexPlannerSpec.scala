package filodb.coordinator.queryplanner

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import filodb.coordinator.ShardMapper
import filodb.core.MetricsTestData
import filodb.core.metadata.Schemas
import filodb.core.query.{ColumnFilter, PlannerParams, PromQlQueryParams, QueryConfig, QueryContext}
import filodb.core.query.Filter.Equals
import filodb.prometheus.ast.TimeStepParams
import filodb.prometheus.parse.Parser
import filodb.query.InstantFunctionId.{Exp, HistogramQuantile}
import filodb.query.exec._

class ShardKeyRegexPlannerSpec extends AnyFunSpec with Matchers with ScalaFutures {

  private val dataset = MetricsTestData.timeseriesDatasetMultipleShardKeys
  private val dsRef = dataset.ref
  private val schemas = Schemas(dataset.schema)
  private implicit val system = ActorSystem()
  private val node = TestProbe().ref

  private val routingConfigString = "routing {\n  buddy {\n    http {\n      timeout = 10.seconds\n    }\n  }\n}"
  private val routingConfig = ConfigFactory.parseString(routingConfigString)
  private val config = ConfigFactory.load("application_test.conf").getConfig("filodb.query").
    withFallback(routingConfig)
  private val queryConfig = new QueryConfig(config)

  private val promQlQueryParams = PromQlQueryParams("sum(heap_usage)", 100, 1, 1000)

  private val localMapper = new ShardMapper(32)
  for {i <- 0 until 32} localMapper.registerNode(Seq(i), node)


  val localPlanner = new SingleClusterPlanner(dsRef, schemas, localMapper, earliestRetainedTimestampFn = 0, queryConfig)

  it("should generate Exec plan for simple query") {
    val lp = Parser.queryToLogicalPlan("test{_ws_ = \"demo\", _ns_ =~ \"App.*\", instance = \"Inst-1\" }", 1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-2"))))}
    val engine = new ShardKeyRegexPlanner(dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[MultiPartitionDistConcatExec] shouldEqual(true)
    execPlan.children(0).children.head.isInstanceOf[MultiSchemaPartitionsExec]
    execPlan.children(1).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-1"))) shouldEqual(true)
    execPlan.children(0).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-2"))) shouldEqual(true)
  }

  it("should generate Exec plan for Aggregate query") {
    val lp = Parser.queryToLogicalPlan("sum(test{_ws_ = \"demo\", _ns_ =~ \"App.*\", instance = \"Inst-1\" })",
      1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-2"))))}
    val engine = new ShardKeyRegexPlanner( dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = PromQlQueryParams("sum(heap_usage)", 100, 1,
      1000)))
    execPlan.isInstanceOf[MultiPartitionReduceAggregateExec] shouldEqual(true)
    execPlan.children(0).children.head.isInstanceOf[MultiSchemaPartitionsExec]
    execPlan.children(1).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-1"))) shouldEqual(true)
    execPlan.children(0).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-2"))) shouldEqual(true)
  }

  it("should generate Exec plan for time()") {
    val lp = Parser.queryToLogicalPlan("time()", 1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq((Seq.empty)) }
    val engine = new ShardKeyRegexPlanner(dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[TimeScalarGeneratorExec] shouldEqual(true)
  }

  it("should generate Exec plan for Scalar Binary Operation") {
    val lp = Parser.queryToLogicalPlan("1 + test{_ws_ = \"demo\", " +
      "_ns_ =~ \"App.*\", instance = \"Inst-1\" }", 1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-2"))))}
    val engine = new ShardKeyRegexPlanner(dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = PromQlQueryParams("1 + test{_ws_ = \"demo\"," +
      " _ns_ =~ \"App.*\", instance = \"Inst-1\" }", 100, 1, 1000)))
    execPlan.isInstanceOf[MultiPartitionDistConcatExec] shouldEqual(true)
    execPlan.rangeVectorTransformers(0).isInstanceOf[ScalarOperationMapper] shouldEqual true
    execPlan.children(0).children.head.isInstanceOf[MultiSchemaPartitionsExec]

    // Child plans should have only inner periodic query in PromQlQueryParams
    execPlan.children(1).children.head.queryContext.origQueryParams.asInstanceOf[PromQlQueryParams].promQl shouldEqual
      "test{instance=\"Inst-1\",_ws_=\"demo\",_ns_=\"App-1\"}"
    execPlan.children(0).children.head.queryContext.origQueryParams.asInstanceOf[PromQlQueryParams].promQl shouldEqual
      "test{instance=\"Inst-1\",_ws_=\"demo\",_ns_=\"App-2\"}"
    execPlan.children(0).children.head.rangeVectorTransformers(0).isInstanceOf[PeriodicSamplesMapper] shouldEqual true
    execPlan.children(1).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-1"))) shouldEqual(true)
    execPlan.children(0).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-2"))) shouldEqual(true)
  }

  it("should generate Exec plan for Binary join without regex") {
    val lp = Parser.queryToLogicalPlan("test1{_ws_ = \"demo\", _ns_ = \"App\"} + " +
      "test2{_ws_ = \"demo\", _ns_ = \"App\"}", 1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-2"))))}
    val engine = new ShardKeyRegexPlanner(dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[BinaryJoinExec] shouldEqual(true)
  }

  it ("should generate Exec plan for Metadata query") {
    val lp = Parser.metadataQueryToLogicalPlan("http_requests_total{job=\"prometheus\", method=\"GET\"}",
      TimeStepParams(1000, 1000, 3000))
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-2"))))}
    val engine = new ShardKeyRegexPlanner(dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[PartKeysDistConcatExec] shouldEqual (true)
  }

  it("should generate Exec plan for histogram quantile for Aggregate query") {
    val lp = Parser.queryToLogicalPlan("histogram_quantile(0.2, sum(test{_ws_ = \"demo\", _ns_ =~ \"App.*\"}))",
      1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-2"))))}
    val engine = new ShardKeyRegexPlanner( dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[MultiPartitionReduceAggregateExec] shouldEqual(true)
    execPlan.asInstanceOf[MultiPartitionReduceAggregateExec].rangeVectorTransformers(0).
      isInstanceOf[AggregatePresenter] shouldEqual true
    execPlan.asInstanceOf[MultiPartitionReduceAggregateExec].rangeVectorTransformers(1).
      isInstanceOf[InstantVectorFunctionMapper] shouldEqual true

    execPlan.asInstanceOf[MultiPartitionReduceAggregateExec].rangeVectorTransformers(1).
      asInstanceOf[InstantVectorFunctionMapper].function shouldEqual HistogramQuantile
    execPlan.children(0).children.head.isInstanceOf[MultiSchemaPartitionsExec]

    //Plan for each map should not have histogram quantile
    execPlan.children(0).children.head.rangeVectorTransformers.length shouldEqual 2
    execPlan.children(0).children.head.rangeVectorTransformers(0).isInstanceOf[PeriodicSamplesMapper] shouldEqual true
    execPlan.children(0).children.head.rangeVectorTransformers(1).isInstanceOf[AggregateMapReduce] shouldEqual true
    execPlan.children(1).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-1"))) shouldEqual(true)
    execPlan.children(0).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-2"))) shouldEqual(true)

    // Child plans should have only sum query in PromQlQueryParams
    execPlan.children(1).children.head.queryContext.origQueryParams.asInstanceOf[PromQlQueryParams].promQl shouldEqual
      "sum(test{_ws_=\"demo\",_ns_=\"App-1\"})"
    execPlan.children(0).children.head.queryContext.origQueryParams.asInstanceOf[PromQlQueryParams].promQl shouldEqual
      "sum(test{_ws_=\"demo\",_ns_=\"App-2\"})"
  }

  it("should generate Exec plan for exp for Aggregate query") {
    val lp = Parser.queryToLogicalPlan("""exp(sum(test{_ws_ = "demo", _ns_ =~ "App.*"}))""",
      1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-2"))))}
    val engine = new ShardKeyRegexPlanner( dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[MultiPartitionReduceAggregateExec] shouldEqual(true)
    execPlan.asInstanceOf[MultiPartitionReduceAggregateExec].rangeVectorTransformers(0).
      isInstanceOf[AggregatePresenter] shouldEqual true
    execPlan.asInstanceOf[MultiPartitionReduceAggregateExec].rangeVectorTransformers(1).
      isInstanceOf[InstantVectorFunctionMapper] shouldEqual true

    execPlan.asInstanceOf[MultiPartitionReduceAggregateExec].rangeVectorTransformers(1).
      asInstanceOf[InstantVectorFunctionMapper].function shouldEqual Exp
    execPlan.children(0).children.head.isInstanceOf[MultiSchemaPartitionsExec]

    //Plan for each map should not have exp
    execPlan.children(0).children.head.rangeVectorTransformers.length shouldEqual 2
    execPlan.children(0).children.head.rangeVectorTransformers(0).isInstanceOf[PeriodicSamplesMapper] shouldEqual true
    execPlan.children(0).children.head.rangeVectorTransformers(1).isInstanceOf[AggregateMapReduce] shouldEqual true
    execPlan.children(1).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-1"))) shouldEqual(true)
    execPlan.children(0).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-2"))) shouldEqual(true)

    // Child plans should have only sum query in PromQlQueryParams
    execPlan.children(1).children.head.queryContext.origQueryParams.asInstanceOf[PromQlQueryParams].promQl shouldEqual
      "sum(test{_ws_=\"demo\",_ns_=\"App-1\"})"
    execPlan.children(0).children.head.queryContext.origQueryParams.asInstanceOf[PromQlQueryParams].promQl shouldEqual
      "sum(test{_ws_=\"demo\",_ns_=\"App-2\"})"
  }

  it("should generate local Exec plan for query without regex") {
    val lp = Parser.queryToLogicalPlan("""test{_ws_ = "demo", _ns_ = "App-1" }""", 1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-2"))))}
    val engine = new ShardKeyRegexPlanner(dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[LocalPartitionDistConcatExec] shouldEqual(true)
    execPlan.children(0).isInstanceOf[MultiSchemaPartitionsExec]
    execPlan.children(0).asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-1"))) shouldEqual(true)
  }

  it("should generate Exec plan for scalar - time()") {
    val lp = Parser.queryToLogicalPlan("""scalar(test{_ws_ = "demo", _ns_ =~ "App.*"}) - time()""",
      1000, 1000)
    val promQlQueryParams = PromQlQueryParams("""scalar(test{_ws_ = "demo", _ns_ =~ "App.*"}) - time()""", 100, 1, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-2"))))}
    val engine = new ShardKeyRegexPlanner( dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[TimeScalarGeneratorExec] shouldEqual(true)
    execPlan.rangeVectorTransformers.head.isInstanceOf[ScalarOperationMapper] shouldEqual true
    execPlan.rangeVectorTransformers.head.asInstanceOf[ScalarOperationMapper].funcParams.head.
      isInstanceOf[ExecPlanFuncArgs] shouldEqual true
    execPlan.rangeVectorTransformers.head.asInstanceOf[ScalarOperationMapper].funcParams.head.
      isInstanceOf[ExecPlanFuncArgs] shouldEqual true
    execPlan.rangeVectorTransformers.head.asInstanceOf[ScalarOperationMapper].funcParams.head.
      asInstanceOf[ExecPlanFuncArgs].execPlan.isInstanceOf[MultiPartitionDistConcatExec] shouldEqual true

    val multiPartitionExec = execPlan.rangeVectorTransformers.head.asInstanceOf[ScalarOperationMapper].funcParams.head.
      asInstanceOf[ExecPlanFuncArgs].execPlan
    multiPartitionExec.rangeVectorTransformers.head.isInstanceOf[ScalarFunctionMapper] shouldEqual true

    // Child plans should have only inner query in PromQlQueryParams
    multiPartitionExec.children(1).children.head.queryContext.origQueryParams.asInstanceOf[PromQlQueryParams].promQl shouldEqual
      "test{_ws_=\"demo\",_ns_=\"App-1\"}"
    multiPartitionExec.children(0).children.head.queryContext.origQueryParams.asInstanceOf[PromQlQueryParams].promQl shouldEqual
      "test{_ws_=\"demo\",_ns_=\"App-2\"}"
  }

  it ("should generate Exec plan for Metadata Label values query") {
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => Seq.empty
    val engine = new ShardKeyRegexPlanner( dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val lp = Parser.labelValuesQueryToLogicalPlan(Seq("""__metric__"""), Some("""_ws_="demo", _ns_=~".*" """),
      TimeStepParams(1000, 20, 5000) )

    val promQlQueryParams = PromQlQueryParams(
      "", 1000, 20, 5000, Some("/api/v2/label/values"))

    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams,  plannerParams =
      PlannerParams(processMultiPartition = true)))

    execPlan.isInstanceOf[LabelValuesDistConcatExec] shouldEqual (true)
  }

  it("should generate Exec plan for Binary join with regex") {
    val lp = Parser.queryToLogicalPlan("test1{_ws_ = \"demo\", _ns_ =~ \"App.*\"} + " +
      "test2{_ws_ = \"demo\", _ns_ =~ \"App.*\"}", 1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => { Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
      ColumnFilter("_ns_", Equals("App-2"))))}
    val engine = new ShardKeyRegexPlanner(dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[BinaryJoinExec] shouldEqual(true)
    execPlan.children(0).isInstanceOf[MultiPartitionDistConcatExec] shouldEqual(true)
    val lhs = execPlan.children(0).asInstanceOf[MultiPartitionDistConcatExec]
    lhs.children.length shouldEqual 2
    lhs.children(0).children.head.isInstanceOf[MultiSchemaPartitionsExec] shouldEqual true
    lhs.children(1).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-1"))) shouldEqual(true)
    lhs.children(0).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-2"))) shouldEqual(true)
    val rhs = execPlan.children(1).asInstanceOf[MultiPartitionDistConcatExec]
    rhs.children(0).children.head.isInstanceOf[MultiSchemaPartitionsExec] shouldEqual true
    rhs.children(1).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-1"))) shouldEqual(true)
    rhs.children(0).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-2"))) shouldEqual(true)
  }

  it("should generate Exec plan for Binary join with regex only on one side") {
    val lp = Parser.queryToLogicalPlan("test1{_ws_ = \"demo\", _ns_ = \"App-0\"} + " +
      "test2{_ws_ = \"demo\", _ns_ =~ \"App.*\"}", 1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => {
      Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
        ColumnFilter("_ns_", Equals("App-1"))), Seq(ColumnFilter("_ws_", Equals("demo")),
        ColumnFilter("_ns_", Equals("App-2"))))
    }
    val engine = new ShardKeyRegexPlanner(dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[BinaryJoinExec] shouldEqual (true)
    execPlan.children(0).isInstanceOf[LocalPartitionDistConcatExec] shouldEqual (true)
    val lhs = execPlan.children(0).asInstanceOf[LocalPartitionDistConcatExec]
    lhs.children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-0"))) shouldEqual (true)
    val rhs = execPlan.children(1).asInstanceOf[MultiPartitionDistConcatExec]
    rhs.children(0).children.head.isInstanceOf[MultiSchemaPartitionsExec] shouldEqual true
    rhs.children(1).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-1"))) shouldEqual (true)
    rhs.children(0).children.head.asInstanceOf[MultiSchemaPartitionsExec].filters.
      contains(ColumnFilter("_ns_", Equals("App-2"))) shouldEqual (true)
  }

  it("should generate Exec plan for topk query with single matching value for regex") {
    val lp = Parser.queryToLogicalPlan(s"""topk(2, test{_ws_ = "demo", _ns_ =~ "App-1"})""",
      1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => {
      Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
        ColumnFilter("_ns_", Equals("App-1"))))
    }
    val engine = new ShardKeyRegexPlanner(dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    execPlan.isInstanceOf[LocalPartitionReduceAggregateExec] shouldEqual (true)
  }

  it("should throw UnsupportedOperationException for topk query with multiple matching values for regex") {
    val lp = Parser.queryToLogicalPlan(s"""topk(2, test{_ws_ = "demo", _ns_ =~ "App.*"})""",
      1000, 1000)
    val shardKeyMatcherFn = (shardColumnFilters: Seq[ColumnFilter]) => {
      Seq(Seq(ColumnFilter("_ws_", Equals("demo")),
        ColumnFilter("_ns_", Equals("App-1"))),
        Seq(ColumnFilter("_ws_", Equals("demo")),
          ColumnFilter("_ns_", Equals("App-2"))))
    }
    val engine = new ShardKeyRegexPlanner(dataset, localPlanner, shardKeyMatcherFn, queryConfig)
    the[UnsupportedOperationException] thrownBy {
      val execPlan = engine.materialize(lp, QueryContext(origQueryParams = promQlQueryParams))
    } should have message "Shard Key regex not supported for TopK"
  }
}
