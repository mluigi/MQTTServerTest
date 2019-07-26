<html>
<head>
    <script type="text/javascript" src="https://code.jquery.com/jquery-3.4.1.min.js"></script>
    <link href="https://modesta.alexflipnote.dev/css/modesta.min.css" type="text/css" rel="stylesheet">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1.0, user-scalable=no"/>
    <title>MQTT Test</title>
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
   <!-- <a class="btn cubered-flat cancelButton">Reset</a>-->
</section>

<section class="card-container" style="margin: auto; height:75vh; width:90vw">
    <canvas id="myChart" class="card" width="5" height="2"></canvas>
    <canvas id="myChart2" class="card" width="5" height="2"></canvas>
    <canvas id="LossChart" class="card" width="5" height="2"></canvas>
</section>


<script id="source" type="text/javascript">
    $(function () {
        $(".cancelButton").click(function () {
            $.ajax({
                url: 'cancel.php'       //integra il codice
            });
        });
        const myChart = new Chart($('#myChart'), {
            type: 'line',
            data: {
                labels: [],
                datasets: [
                    {
                        label: "Tempi tra gli ultimi 500 pacchetti (in ns) - Client 1",
                        data: [],
                        borderWidth: 1,
                        backgroundColor: 'rgb(155, 155, 155)',
                        borderColor: 'rgb(0,76,255)'
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
                            return parseFloat(val).toFixed(2) + "ms";
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
                        label: "Differenza pacchetti QOS0 mandati e ricevuti",
                        data: [],
                        borderWidth: 1,
                        backgroundColor: 'rgba(155,155,155,0)',
                        borderColor: 'rgb(255,0,64)'
                    },
                    {
                        label: "Differenza pacchetti QOS1 mandati e ricevuti",
                        data: [],
                        borderWidth: 1,
                        backgroundColor: 'rgba(155,155,155,0)',
                        borderColor: 'rgb(167,236,255)'
                    },
                    {
                        label: "Differenza puback mandati e ricevuti",
                        data: [],
                        borderWidth: 1,
                        backgroundColor: 'rgba(155,155,155,0)',
                        borderColor: 'rgb(231,49,255)'
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
                }
            }
        });

        const myChart2 = new Chart($('#myChart2'), {
            type: 'line',
            data: {
                labels: [],
                datasets: [
                    {
                        label: "Tempi tra gli ultimi 500 pacchetti (in ns) - Client 2",
                        data: [],
                        borderWidth: 1,
                        backgroundColor: 'rgba(155,155,155,0)',
                        borderColor: 'rgb(179,255,0)'
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


        let diffQoS0 = [0];
        let diffQoS1 = [0];
        let diffPub = [0];

        function update() {
            $.ajax({
                url: 'api.php',     //integra codice
                data: "",
                dataType: 'json',
                success: function (data) {
                    let dati = data[0];     //prendo la prima colonna del database
                    let qos0 = dati["QOS0PacketsReceived"];
                    let qos1 = dati["QOS1PacketsReceived"];
                    let totale = parseInt(qos0) + parseInt(qos1);
                    let timesArray = data[1].map(Number).filter(function (value) {
                        return value < 700_000_000
                    });//prendo la seconda colonna del database

                    var sum = 0;
                    timesArray.forEach(function (it) {      //sommo i tempi
                        sum += it
                    });
                    let avg = sum / timesArray.length;      //media

                    $(".min").text(Math.min.apply(Math, timesArray) + "ns");
                    $(".max").text(parseFloat(Math.max.apply(Math, timesArray) / 1000000).toFixed(2) + "ms");
                    $(".media").text(parseFloat(avg / 1000000).toFixed(2) + "ms");
                    $(".ricevuti").text(totale);
                    $(".qos0").text(qos0);
                    $(".qos1").text(qos1);
                    $(".puback").text(dati["pubackSent"]);

                    let idArray = data[2].map(Number);      //prendo la terza colonna del database
		    let a = dati["QOS0PacketsSent"] - dati["QOS0PacketsReceived"];
		    diffQoS0.push(a>0?a:0);
		    a=dati["QOS1PacketsSent"] - dati["QOS1PacketsReceived"];
		    diffQoS1.push(a>0?a:0);
		    a=dati["pubackSent"] - dati["pubackReceived"];
                    diffPub.push(a>0?a:0);

                    let indexesLossArray = [];        //dichiaro un vettore di indici
                    for (let key of diffQoS1.keys()) {
                        indexesLossArray.push(key);
                    }
                    lossChart.data.datasets[0].data = diffQoS0;         //metto i dati sul grafico
                    lossChart.data.datasets[1].data = diffQoS1;
                    lossChart.data.datasets[2].data = diffPub;
                    lossChart.data.labels = indexesLossArray;
                    let devId = data[3].map(Number);        //prendo la quarta colonna del database

                    //creo degli array in cui splittare idArray e timesArray
                    let idArray1 = [];
                    let TimesArray1 = [];
                    let idArray2 = [];
                    let TimesArray2 = [];

                    //splitto idArray e timesArray a seconda del client
                    for (i = 0; i < idArray.length; i++) {
                        if (devId[i] === devId[0]) {
                            idArray1.push(idArray[i]);
                            TimesArray1.push(timesArray[i]);
                        } else {
                            idArray2.push(idArray[i]);
                            TimesArray2.push(timesArray[i]);
                        }
                    }
                    //metto i dati sui grafici
                    myChart.data.labels = idArray1;
                    myChart.data.datasets[0].data = TimesArray1;
                    myChart2.data.labels = idArray2;
                    myChart2.data.datasets[0].data = TimesArray2;

                    //aggiorno i grafici se ci sono variazioni di dati
                    myChart.update();
                    myChart2.update();
                    lossChart.update();
                }
            });
            setTimeout(update, 500)
        }

        update()
    });

</script>
<script src="https://cdn.jsdelivr.net/npm/chart.js@2.8.0"></script>
</body>
</html>
