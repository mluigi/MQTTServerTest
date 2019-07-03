<html>
<head>
    <script language="javascript" type="text/javascript" src="https://code.jquery.com/jquery-3.4.1.min.js"></script>
    <link href="https://modesta.alexflipnote.dev/css/modesta.min.css" type="text/css" rel="stylesheet">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1.0, user-scalable=no"/>
</head>
<body>
<section class="card-container">
    <div class="card">
        <h1 class="title">Totale pacchetti ricevuti</h1>
        <p class="description ricevuti">0</p>
    </div>
    <div class="card">
        <h1 class="title">Pacchetti QOS0</h1>
        <p class="description qos0">0</p>
    </div>
    <div class="card">
        <h1 class="title">Pacchetti QOS1</h1>
        <p class="description qos1">0</p>
    </div>
    <div class="card">
        <h1 class="title">PUBACK mandati</h1>
        <p class="description puback">0</p>
    </div>

</section>
<section class="card-container">
    <div class="card">
        <h1 class="title">Min</h1>
        <p class="description min">0</p>
    </div>

    <div class="card">
        <h1 class="title">Max</h1>
        <p class="description max">0</p>
    </div>
    <div class="card">
        <h1 class="title">Media</h1>
        <p class="description media">0</p>
    </div>
</section>
<section class="card-container" style="margin: auto; height:75vh; width:90vw">
    <canvas id="myChart" class="card" width="5" height="2"></canvas>
    <canvas id="LossChart" class="card" width="5" height="2"></canvas>
</section>
<script id="source" type="text/javascript">
    $(function () {
        let ctx = $('#myChart');
        const myChart = new Chart(ctx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [
                    {
                        label: "Tempi tra gli ultimi 500 pacchetti (in ns)",
                        data: [],
                        borderWidth: 1,
                        backgroundColor: 'rgb(155, 155, 155)',
                        borderColor: 'rgb(100,100,100)'
                    },
                ]
            },
            options: {
                maintainAspectRatio: false,
                scales: {
                    yAxes: [{
                        ticks: {
                            beginAtZero: true
                        }
                    }]
                },
                tooltips: {
                    callbacks: {
                        label: function (tooltipItem, data) {
                            let val = tooltipItem.yLabel / 1000000;
                            let label = parseFloat(val).toFixed(2) + "ms";
                            return label;
                        }
                    }
                }
            }
        });
        const lossChart = new Chart($('#LossChart'), {
            type: 'line',
            data: {
                labels: [],
                datasets: [
                    {
                        label: "PacketLoss(t)",
                        data: [],
                        borderWidth: 1,
                        backgroundColor: 'rgb(155, 155, 155)',
                        borderColor: 'rgb(100,100,100)'
                    },
                ]
            },
            options: {
                maintainAspectRatio: false,
                scales: {
                    yAxes: [{
                        ticks: {
                            beginAtZero: true
                        }
                    }]
                },
                tooltips: {
                    callbacks: {
                        label: function (tooltipItem, data) {
                            let val = tooltipItem.yLabel / 1000000;
                            let label = parseFloat(val).toFixed(2) + "ms";
                            return label;
                        }
                    }
                }
            }
        });

        let lossArray = [];

        function update() {
            $.ajax({
                url: 'api.php',
                data: "",
                dataType: 'json',
                success: function (data) {
                    let dati = data[0];
                    let qos0 = dati["QOS0Packets"];
                    let qos1 = dati["QOS1Packets"];
                    let totale = parseInt(qos0) + parseInt(qos1);
                    let timesArray = data[1].map(Number);
                    var sum = 0;
                    timesArray.forEach(function (it) {
                        sum += it
                    });
                    let avg = sum / timesArray.length;
                    $(".min").text(Math.min.apply(Math, timesArray) + "ns");
                    $(".max").text(parseFloat(Math.max.apply(Math, timesArray) / 1000000).toFixed(2) + "ms");
                    $(".media").text(parseFloat(avg / 1000000).toFixed(2) + "ms");
                    $(".ricevuti").text(totale);
                    $(".qos0").text(qos0);
                    $(".qos1").text(qos1);
                    $(".puback").text(dati["packetsSent"]);
                    let idArray = data[2].map(Number);
                    lossArray.push(qos1-dati["packetsSent"]);
                    lossChart.data.datasets[0].data = lossArray;
                    lossChart.data.labels = lossArray.keys();
                    myChart.data.labels = idArray;
                    myChart.data.datasets[0].data = timesArray;
                    myChart.update();
                    lossChart.update();
                }
            });
            setTimeout(update, 2000)
        }

        update()
    });

</script>
<script src="https://cdn.jsdelivr.net/npm/chart.js@2.8.0"></script>
</body>
</html>